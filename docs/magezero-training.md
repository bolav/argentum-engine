# MageZero-Compatible Training Pipeline

This document describes the state encoding, action encoding, and training data format used to produce
self-play data compatible with a [MageZero](https://github.com/WillWroble/MageZero)-style PyTorch
EmbeddingBag network.

---

## Background: why sparse binary hashing

MageZero represents game state as a **sparse binary vector**: a sorted set of integer indices, where
each active index represents a feature that is present in the current game state. The network uses a
PyTorch `EmbeddingBag(mode="sum")` that looks up embeddings for each active index and sums them —
effectively a learned sparse linear layer over features.

The key properties of this approach:

- **Variable-length input.** MTG states differ wildly in complexity (0 permanents vs 30+ permanents,
  empty stack vs nested triggers). A sparse index set handles this naturally; a fixed-length dense
  vector either truncates or pads.
- **Feature reuse across cards.** Two different cards that are both "2/2 Creature — Goblin" share
  all their features. The network learns from the feature, not the specific card's slot in a
  fixed-size array.
- **Stable across training runs.** As long as feature strings hash to the same indices (guaranteed by
  `FeatureMap`), training data from different sessions is commensurable.

---

## `FeatureMap`

**Location:** `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/makezero/FeatureMap.kt`

A thread-safe monotonically-growing map from `String → Int`. Once a string is assigned an index it
never changes. The map can only grow — no deletions, no reassignments.

```kotlin
class FeatureMap(val path: Path? = null) {
    fun id(feature: String): Int   // assign-or-retrieve
    fun size(): Int
    fun save()                     // write to path as JSON { "version": 1, "map": {"feature": 42, ...} }
    fun load()
    companion object {
        fun load(path: Path): FeatureMap
        fun inMemory(): FeatureMap  // for tests
    }
}
```

### Persistence

The file format is a simple JSON object:

```json
{
  "version": 1,
  "entries": [
    ["UPKEEP", 0],
    ["MAIN_PHASE_ONE", 1],
    ["Player.LifeTotal:20", 2],
    ...
  ]
}
```

`entries` is an array-of-pairs (not a JSON object) so insertion order is preserved and the file can
be appended to incrementally. On load, the map is rebuilt in O(n).

### One map per featurizer version, shared by all agents

The FeatureMap is a **vocabulary**, not a model artifact. It belongs to the featurizer
implementation, not to any individual trained AI agent. All agents trained with the same
`MageZeroStateFeaturizer` version **must share the same map** — indices from different maps are not
commensurable and training data cannot be pooled across them.

Think of it like a tokenizer in NLP: you maintain one vocabulary for the project, and all model
checkpoints record which vocabulary version they were built against.

**The map version changes only when the featurizer logic changes** (new namespaces, different numeric
bucketing, new decision types). That is a deliberate versioning decision, not something that happens
per game or per agent.

Recommended layout:

```
data/
  features/
    magezero-v1.json          ← shared FeatureMap for MageZeroStateFeaturizer v1
  self-play/
    YYYYMMDD-{runId}.jsonl    ← training rows referencing magezero-v1 indices
  agents/
    makezero-v1/
      checkpoint-epoch-10.pt  ← model weights (feature_dim fixed at training time)
      meta.json               ← { "featureMap": "features/magezero-v1.json", "featureDim": 84201 }
    makezero-v2/
      ...
```

`meta.json` is important: when loading a checkpoint the embedding layer must be initialized to the
`featureDim` it was trained with. As the map grows after training, add new embedding rows
(initialized to zero) before running inference — do not retrain from scratch.

The configurable path defaults to `data/features/magezero-v1.json`
(`gym.trainer.feature-map-path`).

### Sharing across parallel workers

When running multiple parallel self-play workers, each worker loads the shared map at startup, then
calls `FeatureMap.id()` locally. New features discovered by each worker are merged back with a
file-level write lock at `save()` time. A simpler alternative for large-scale runs is a single
coordinator process that owns the map and workers call it over HTTP.

---

## `MageZeroStateFeaturizer`

**Location:** `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/makezero/MageZeroStateFeaturizer.kt`

Implements `StateFeaturizer<SparseFeatures>`. Given a `TrainerContext`, walks the `GameState` and
produces a sorted `IntArray` of active feature indices.

### Namespace structure

Features are named as dot-separated path strings. The featurizer builds them by concatenation:

```
{turnStep}
{decisionType}
Player.LifeTotal:{N}
Player.LibraryCount:{N}
Player.CanPlayLand
Player.IsActivePlayer
Player.IsDecisionPlayer
Player.ManaPool.W:{N}
Player.ManaPool.U:{N}
Player.ManaPool.B:{N}
Player.ManaPool.R:{N}
Player.ManaPool.G:{N}
Player.ManaPool.C:{N}
Player.Hand.{CardName}.Card
Player.Hand.{CardName}.{CardType}
Player.Hand.{CardName}.{Color}Card
Player.Hand.{CardName}.{SubType}
Player.Hand.{CardName}.ManaValue:{N}
Player.Hand.{CardName}.{ManaPip}
Player.Hand.{CardName}.Ability.{ruleText}
Player.Battlefield.{CardName}.Card
Player.Battlefield.{CardName}.Permanent
Player.Battlefield.{CardName}.{CardType}_dynamic
Player.Battlefield.{CardName}.{Color}Card_dynamic
Player.Battlefield.{CardName}.Tapped
Player.Battlefield.{CardName}.SummoningSick
Player.Battlefield.{CardName}.CanAttack
Player.Battlefield.{CardName}.CanBlock
Player.Battlefield.{CardName}.Attacking
Player.Battlefield.{CardName}.Power:{N}
Player.Battlefield.{CardName}.Toughness:{N}
Player.Battlefield.{CardName}.Damage:{N}
Player.Battlefield.{CardName}.Counter.{CounterName}:{N}
Player.Battlefield.{CardName}.Keyword.{Name}
Player.Battlefield.{CardName}.DynamicAbility.{ruleText}
Player.Graveyard.{CardName}.Card
Player.Graveyard.{CardName}.{CardType}
... (same card fields as Hand, no dynamic battlefield fields)
Opponent.{...}                (same structure as Player, hand is hidden → CardsInHand:{N} only)
Stack.{SpellName}.isController    (if acting player controls the spell)
Stack.{SpellName}.{ruleText}
Stack.{SpellName}.Target.{targetName}
Exile.{ZoneName}.{CardName}.{...}
```

### Numeric encoding

Numeric values are encoded as `{key}:{value}` strings so each value maps to its own feature index.
This matches MageZero's approach and avoids the need for separate scalar slots in the embedding bag.

```kotlin
// Life = 12 → "Player.LifeTotal:12"
// Power = 3 → "Player.Battlefield.Grizzly Bears.Power:3"
// Mana (green = 2) → "Player.ManaPool.G:2"
```

Values are clamped to avoid feature explosion: `lifeTotal` is clamped at 40, `power`/`toughness` at
20, `libraryCount` is bucketed (0, 1–5, 6–10, 11–20, 21+).

### Projected state requirement

All battlefield queries use **projected state** — this is a load-bearing rule in the engine (see
`CLAUDE.md`):

```kotlin
val projected = ctx.state.projectedState

// Correct: uses projected state
if (projected.isCreature(entityId)) { ... }
val power = projected.getPower(entityId)
val toughness = projected.getToughness(entityId)

// Wrong: uses base CardComponent — misses layer effects
val card = entity.get<CardComponent>()
if (card.typeLine.isCreature) { ... }
```

### Decision type

The decision type is added as a top-level feature so the network can condition on what kind of choice
is being made:

```kotlin
val decisionType = when (ctx.pendingDecision) {
    null                            -> "PRIORITY"
    is YesNoDecision               -> "YES_NO"
    is ChooseTargetsDecision       -> "CHOOSE_TARGET"
    is ChooseModeDecision          -> "CHOOSE_MODE"
    is SelectCardsDecision         -> "SELECT_CARDS"
    is DistributeDecision          -> "DISTRIBUTE"
    is SelectManaSourcesDecision   -> "SELECT_MANA"
    else                           -> "OTHER_DECISION"
}
```

### Multiple permanents with the same name

When a player controls multiple copies of the same card, each copy gets the same feature strings —
they are merged in the sparse index set. This is the same behaviour as MageZero: the network sees "2
instances of Grizzly Bears worth of features". If distinguishing copies is important (e.g. one is
tapped, one is not), both the `Tapped` feature and the lack of it appear in the index set for that
card name.

---

## `MageZeroActionFeaturizer`

**Location:** `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/makezero/MageZeroActionFeaturizer.kt`

Implements `ActionFeaturizer`. Exposes four policy heads matching MageZero's architecture.

### Policy heads

| Head | Slot count | ActionType int | Covers |
|---|---|---|---|
| `priority` (player) | 128 | `0` | `CastSpell`, `ActivateAbility`, `PassPriority`, `PlayLand` — when `isPlayer=True` |
| `priority` (opponent) | 128 | `0` | Same action types — when `isPlayer=False` |
| `target` | 128 | `3` | `ChooseTargetsDecision` responses |
| `binary` | 2 | `5` | `DeclareAttackers`, `DeclareBlockers`, `YesNoDecision` |

Head sizes from `model.py`: `PRIORITY_A_MAX = PRIORITY_B_MAX = TARGETS_MAX = 128`, `BINARY_MAX = 2`.
The full `ActionType` enum is `PRIORITY=0, CHOOSE_NUM=1, BLANK=2, CHOOSE_TARGET=3, MAKE_CHOICE=4,
CHOOSE_USE=5` — only 0, 3, and 5 are actively trained.

**Slot assignment uses Java's `String.hashCode()`** (not MurmurHash). Slot 0 is reserved for
`"Pass"` in priority heads and `"Stop Choosing"` in target heads. Basic land tap abilities
(`"{T}: Add {R}."` etc.) are hardcoded to slots 1–6. All other actions hash to slots 1–127 via
`abs(java_hash(name)) % 127 + 1`. See `magezero-first-run.md` for the full Python implementation.

Head selection depends on `TrainerContext.pendingDecision`:

```kotlin
override fun slot(action: GameAction, ctx: TrainerContext): SlotEncoding {
    val (head, headSize) = when (ctx.pendingDecision) {
        null                                     -> "priority" to 4096
        is YesNoDecision                         -> "binary" to 256
        is ChooseTargetsDecision                 -> "target" to 2048
        is ChooseModeDecision -> when {
            ctx.pendingDecision.maxModes == 1    -> "binary" to 256
            else                                 -> "modal" to 1024
        }
        is SelectCardsDecision -> when {
            ctx.pendingDecision.maxCards == 1    -> "binary" to 256
            else                                 -> "modal" to 1024
        }
        is DistributeDecision                    -> "modal" to 1024
        else                                     -> "modal" to 1024
    }
    val slot = murmur3(canonicalString(action, ctx)) % headSize
    return SlotEncoding(head, slot)
}
```

### Canonical action string

UUID and instance-specific identifiers must be removed from action descriptions before hashing, so
the same logical action in different games hashes to the same slot:

```kotlin
fun canonicalString(action: GameAction, ctx: TrainerContext): String = when (action) {
    is CastSpell -> {
        val name = ctx.state.cardName(action.cardId) ?: "UnknownCard"
        "CastSpell:$name"
    }
    is ActivateAbility -> {
        val name = ctx.state.cardName(action.sourceId) ?: "UnknownSource"
        "ActivateAbility:$name:${action.abilityIndex}"
    }
    is PassPriority -> "PassPriority"
    is PlayLand -> {
        val name = ctx.state.cardName(action.cardId) ?: "UnknownLand"
        "PlayLand:$name"
    }
    is DeclareAttackers -> "DeclareAttackers"
    is DeclareBlockers -> "DeclareBlockers"
    is SubmitDecision -> canonicalDecisionString(action.response, ctx)
    else -> action::class.simpleName ?: "Unknown"
}
```

### Collision rate

With head size 4096, the expected collision probability per pair of distinct actions is 1/4096 ≈
0.024%. In a typical priority decision with 5–15 legal actions, the probability that any two collide
is well under 1%. Verify with a 1000-game sample test before production training.

---

## `MageZeroSelfPlaySink`

**Location:** `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/makezero/MageZeroSelfPlaySink.kt`

Implements `SelfPlaySink<SparseFeatures>`. Buffers rows per game in memory, back-patches the terminal
outcome, then flushes to a JSONL file.

### Output format

One JSON line per decision step:

```json
{
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "step": 14,
  "actingPlayer": "P1",
  "stateFeatures": [12, 45, 789, 1203, 4501],
  "head": "priority",
  "legalSlots": [
    {"head": "priority", "slot": 127},
    {"head": "priority", "slot": 892},
    {"head": "priority", "slot": 3401}
  ],
  "visits": [0, 0, ..., 42, 0, ..., 18, 0, ...],
  "mctsValue": 0.62,
  "outcome": 1.0
}
```

Fields:
- `stateFeatures`: sorted sparse index array
- `head`: which policy head was used for this decision
- `visits`: dense array of MCTS visit counts, indexed by slot within `head`. Length = head size.
- `mctsValue`: root value estimate from MCTS at this step
- `outcome`: back-patched terminal result from `actingPlayer`'s perspective: `+1.0` win, `-1.0` loss, `0.0` draw/truncation

### File layout

```
data/self-play/
  YYYYMMDD-HHMMSS-{runId}.jsonl      ← one file per training run
  feature-map.json                    ← shared, append-only
```

---

## Python ingestion

A minimal PyTorch training loop reads the JSONL and trains an `EmbeddingBag` policy network:

```python
import torch, json
from torch import nn
from torch.nn import EmbeddingBag

class MageZeroNet(nn.Module):
    def __init__(self, feature_dim, embed_dim=256, heads):
        super().__init__()
        self.embed = EmbeddingBag(feature_dim, embed_dim, mode="sum", sparse=True)
        self.value_head = nn.Linear(embed_dim, 1)
        self.policy_heads = nn.ModuleDict({
            name: nn.Linear(embed_dim, size) for name, size in heads.items()
        })

    def forward(self, feature_indices_batch, offsets):
        h = self.embed(feature_indices_batch, offsets)
        value = torch.tanh(self.value_head(h))
        policy = {name: head(h) for name, head in self.policy_heads.items()}
        return value, policy

def load_batch(lines):
    # lines: list of JSON strings from the JSONL file
    features, offsets, visits, outcomes, heads = [], [0], [], [], []
    for line in lines:
        row = json.loads(line)
        features.extend(row["stateFeatures"])
        offsets.append(offsets[-1] + len(row["stateFeatures"]))
        visits.append(row["visits"])
        outcomes.append(row["outcome"])
        heads.append(row["head"])
    return (
        torch.tensor(features, dtype=torch.long),
        torch.tensor(offsets[:-1], dtype=torch.long),
        visits, outcomes, heads
    )
```

The `feature_dim` for `EmbeddingBag` must be at least `FeatureMap.size()` at training time. As new
features are discovered the map grows; periodically re-initialize the embedding layer with the larger
size (embedding weight rows for existing indices are preserved).

---

## Wire contract with `RemoteHttpEvaluator`

When using a live Python inference server during MCTS self-play:

**Request:**
```json
{
  "stateFeatures": [12, 45, 789, 1203],
  "legalSlots": [
    {"head": "priority", "slot": 127},
    {"head": "priority", "slot": 892}
  ],
  "decisionType": "PRIORITY",
  "playerId": "P1"
}
```

**Response:**
```json
{
  "priors": {
    "priority": [0.0, ..., 0.72, ..., 0.28, ...]
  },
  "value": 0.41
}
```

`priors[head]` is a dense array of length `headSize`. Only the slots in `legalSlots` are used by the
MCTS; other slots are ignored. The inference server can return zeros for illegal slots.

Use `RemoteHttpEvaluator.forSparse(url)` to create an evaluator with the correct wire format:

```kotlin
val evaluator = RemoteHttpEvaluator.forSparse("http://localhost:8000/evaluate")
```

---

## Running self-play

### Minimal no-NN run (heuristic evaluator)

```kotlin
val featureMap = FeatureMap.load(Path.of("data/feature-map.json"))
val sink = MageZeroSelfPlaySink(
    path = Path.of("data/self-play/${runId}.jsonl"),
    featureMap = featureMap
)
val loop = SelfPlayLoop(
    envFactory = { GameEnvironment.create(cardRegistry) },
    featurizer = MageZeroStateFeaturizer(featureMap),
    actionFeaturizer = MageZeroActionFeaturizer(),
    evaluator = HeuristicEvaluator(),
    sink = sink,
    simulationsPerMove = 50
)
loop.playGames(count = 1000) { i ->
    GameConfig(
        players = listOf(
            PlayerConfig("P1", someDeck),
            PlayerConfig("P2", someDeck)
        ),
        skipMulligans = true
    )
}
sink.close()
featureMap.save()
```

### With Python NN inference server

```kotlin
val evaluator = RemoteHttpEvaluator.forSparse("http://localhost:8000/evaluate")
val loop = SelfPlayLoop(
    ...,
    evaluator = evaluator,
    simulationsPerMove = 200
)
```

### Parallel self-play

Each parallel worker loads the same `FeatureMap` at startup and calls `featureMap.save()` when done.
Workers write to separate JSONL files. The Python trainer reads all files in the directory.

Do not share a `FeatureMap` instance across threads without external synchronization — `FeatureMap`
is thread-safe internally, but the `save()` call should be coordinated to avoid partial writes.

---

## Testing

### `FeatureMapTest`

- Assigning the same string twice returns the same index.
- Indices are monotonically increasing.
- Save + reload produces identical map.
- Concurrent access from 8 threads produces no duplicates.

### `MageZeroStateFeaturizerTest`

- Identical game states produce identical `SparseFeatures`.
- Indices are sorted (required by PyTorch EmbeddingBag).
- No index exceeds `featureMap.size()` after featurization.
- Battlefield queries use projected state (verify with a state where a layer effect changes a type).
- Decision type changes the index set.

### `MageZeroActionFeaturizerTest`

- `PassPriority` always maps to `priority` head.
- `YesNoDecision` response always maps to `binary` head.
- `ChooseTargetsDecision` response always maps to `target` head.
- Slot values are deterministic across calls.
- Collision rate < 1% in a 100-game sample.

### `MageZeroSelfPlayLoopTest`

End-to-end: 5 games with `HeuristicEvaluator`, write to temp file, assert:
- All JSONL lines parse as valid JSON.
- `stateFeatures` arrays are sorted.
- `outcome` is one of `{-1.0, 0.0, 1.0}`.
- Feature map is stable after save + reload.
