# MageZero First Run — Implementation Plan

Getting MageZero training against Argentum Engine for the first time: from zero to a first
self-play generation that produces a trained model checkpoint.

---

## How MageZero currently works (XMage version)

```
┌──────────────────────────────────┐        msgpack/HTTP       ┌──────────────────────┐
│  XMage JVM                       │ ←───────────────────────→ │  MageZero Python     │
│                                  │                            │  server.py           │
│  • Runs MTG game rules           │  POST /evaluate            │                      │
│  • StateEncoder → sparse indices │  {indices, offsets}        │  • Net: 2M→512→256   │
│  • ActionEncoder → (head, slot)  │  ← {policy_player,         │  • 4 policy heads    │
│  • MCTS tree search              │     policy_opponent,       │    (128+128+128+2)   │
│  • Writes HDF5 training data     │     policy_target,         │  • 1 value head      │
│                                  │     policy_binary, value}  │                      │
└──────────────────────────────────┘                            └──────────────────────┘
                                                                          │
                                                                 train.py reads HDF5
                                                                 updates model.pt.gz
```

The JVM does game execution, feature encoding, MCTS, and data writing. Python only does inference
and training. The runner (`runner.py`) orchestrates: start inference server → launch JVM → train.

**Offline mode** (`mcts.offline_mode: true` in `game.yml`): JVM skips the Python server and uses
uniform priors. This is how generation 0 bootstraps without any model.

---

## Target architecture (Argentum version)

Replace the XMage JVM with a Python client that calls Argentum's `gym-server`:

```
┌──────────────────────────────────┐        HTTP/JSON          ┌──────────────────────┐
│  Python game client (new)        │ ←───────────────────────→ │  gym-server (Kotlin) │
│  magezero/argentum/              │                            │                      │
│  • ArgentumClient → gym-server   │  POST /envs               │  • GameEnvironment   │
│  • ArgentumStateEncoder          │  POST /envs/{id}/step     │  • TrainingObservation│
│  • ArgentumActionEncoder         │  POST /envs/{id}/heuristic-step (new)            │
│  • MCTS (pure Python)            │                            │                      │
│  • HDF5 writer (same format)     │                            └──────────────────────┘
└──────────────────────────────────┘
          │ msgpack/HTTP
          ↓
┌──────────────────────────────────┐
│  MageZero Python (unchanged)     │
│  server.py, train.py, dataset.py │
│  runner.py (partial reuse)       │
└──────────────────────────────────┘
```

`server.py`, `train.py`, and `dataset.py` are **reused as-is**. Only the data-generation side
changes. The HDF5 file format is identical so the training pipeline sees no difference.

---

## Data format (must match exactly)

`train.py` reads HDF5 files via `dataset.py`. Each file contains:

| Dataset | dtype | shape | Meaning |
|---|---|---|---|
| `/indices` | int32 | `[nnz]` | Concatenated sparse feature indices across all states |
| `/offsets` | int64 | `[N+1]` | CSR pointer: state i uses `indices[offsets[i]:offsets[i+1]]` |
| `/row` | float32 | `[N, 132]` | Per-state data: `row[A+0..3]` = metadata |

`row` layout (`A = ACTIONS_MAX = 128`):

| Columns | Meaning |
|---|---|
| `row[:, 0:128]` | Policy label — MCTS visit count distribution (normalized to sum to 1 during training) |
| `row[:, 128]` | Result label — game outcome from this player's perspective: `+1` win, `-1` loss, `0` draw |
| `row[:, 129]` | State score (unused by current train.py, set to `0.0`) |
| `row[:, 130]` | `isPlayer` — `1.0` if this is player A's perspective, `0.0` if opponent's |
| `row[:, 131]` | `actionType` — `0` = PRIORITY, `3` = CHOOSE_TARGET, `5` = CHOOSE_USE |

**Policy label semantics:** The 128 slots are the policy head's action space. Slot assignment is
stable (hash-based, same card/action → same slot across games). The MCTS visit counts are placed
into their corresponding slots; all other slots are `0.0`. The training loss is KL-divergence
against this distribution.

**`isPlayer` controls which head trains:** `train.py` routes states to `player_priority_head`
(`isPlayer=True`) vs `opponent_priority_head` (`isPlayer=False`). This encodes both players'
perspectives in a single model without role confusion.

---

## Neural network (model.py — unchanged)

```
Input: sparse feature indices (subset of 2,000,000-dim binary space)
  → EmbeddingBag(2M, 512, mode='sum', sparse=True)
  → Linear(512 → 256) + ReLU
  ┌─→ player_priority_head:   Linear(256 → 128)   [ActionType.PRIORITY,  isPlayer=True]
  ├─→ opponent_priority_head: Linear(256 → 128)   [ActionType.PRIORITY,  isPlayer=False]
  ├─→ target_head:            Linear(256 → 128)   [ActionType.CHOOSE_TARGET]
  ├─→ binary_head:            Linear(256 → 2)     [ActionType.CHOOSE_USE]
  └─→ value_head:             Linear(256 → 1) + Tanh
```

**Head sizes**: Priority=128, Target=128, Binary=2. All slots are 0-127 (or 0-1 for binary).
Action slot assignment must hash into `[0, 127]` for priority/target, `{0, 1}` for binary.

---

## Python inference server (server.py — unchanged)

Listens on `localhost:50052`. Wire format: **msgpack** (not JSON).

Request:
```python
msgpack.packb({"indices": [12, 45, 789, ...], "offsets": [0]})
```

Response:
```python
{
  "policy_player":   [float, ...],   # 128 logits
  "policy_opponent": [float, ...],   # 128 logits
  "policy_target":   [float, ...],   # 128 logits
  "policy_binary":   [float, float], # 2 logits
  "value":           float
}
```

The `apply_ignore` function in `server.py` removes known-redundant feature indices (from
`ignore.roar`) before inference — the client sends raw indices, the server filters them.

---

## What needs to be built

### In Argentum Engine (gym-server)

#### `HeuristicService` + `POST /envs/{id}/heuristic-step`

Used for both offline generation (replaces XMage's `offline_mode`) and behaviour cloning
pre-training. Runs the heuristic `EngineAiPlayerController` for the current acting player and
returns the chosen action alongside the observation, so Python can record the labelled state.

```kotlin
// gym-server/.../service/HeuristicService.kt
@Service
class HeuristicService(
    private val multiEnvService: MultiEnvService,
    private val cardRegistry: CardRegistry
) {
    fun heuristicStep(envId: EnvId): HeuristicStepResult {
        val env = multiEnvService.getEnv(envId)       // internal access
        val actingPlayer = env.agentToAct ?: error("Game over")
        val ai = AIPlayer.create(cardRegistry, actingPlayer)
        val action = ai.chooseAction(env.state)
        val actionId = env.resolveActionId(action)    // map GameAction → actionId
        val preObs = multiEnvService.observe(envId).observation
        val nextObs = multiEnvService.step(StepRequest(envId, actionId)).observation
        return HeuristicStepResult(preObs, actionId, nextObs)
    }
}

data class HeuristicStepResult(
    val observation: TrainingObservation,
    val heuristicActionId: Int,
    val nextObservation: TrainingObservation
)
```

**Files:**
- `gym-server/.../service/HeuristicService.kt` (new)
- `gym-server/.../controller/EnvController.kt` (add endpoint)
- `gym-server/.../dto/GymDtos.kt` (add `HeuristicStepResult`)

### In MageZero Python (`magezero/argentum/` — new package)

#### `client.py` — gym-server HTTP wrapper

```python
import requests, msgpack

class ArgentumClient:
    def __init__(self, base_url="http://localhost:8090"):
        self.base_url = base_url

    def create_env(self, config: dict) -> tuple[str, dict]:
        r = requests.post(f"{self.base_url}/envs", json=config)
        r.raise_for_status()
        d = r.json()
        return d["envId"]["id"], d["observation"]

    def step(self, env_id: str, action_id: int) -> dict:
        r = requests.post(f"{self.base_url}/envs/{env_id}/step",
                          json={"actionId": action_id})
        r.raise_for_status()
        return r.json()

    def heuristic_step(self, env_id: str) -> dict:
        r = requests.post(f"{self.base_url}/envs/{env_id}/heuristic-step")
        r.raise_for_status()
        return r.json()

    def dispose(self, env_ids: list[str]):
        requests.delete(f"{self.base_url}/envs",
                        json={"envIds": [{"id": e} for e in env_ids]})
```

#### `encoder.py` — `TrainingObservation` → sparse feature indices

Reads the structured JSON observation and produces a sorted `list[int]` of active feature indices,
using a `FeatureMap` (string → int in `[0, GLOBAL_MAX)`).

Feature namespace mirrors MageZero's `StateEncoder.processState()` but reads from JSON fields
instead of walking the XMage game object tree.

```
{step}                                   # e.g. "MAIN_PHASE_ONE"
{phase}                                  # e.g. "PRECOMBAT_MAIN"
{decisionType}                           # "PRIORITY" | "CHOOSE_TARGET" | "CHOOSE_USE"
Player.LifeTotal:{bucket}
Player.LibraryCount:{bucket}
Player.CanPlayLand
Player.IsActivePlayer
Player.HasPriority
Player.ManaPool.{W|U|B|R|G|C}:{n}
Player.Hand.{CardName}.Card
Player.Hand.{CardName}.{type}            # CREATURE, INSTANT, …
Player.Hand.{CardName}.{color}Card       # REDCard, WHITECard, …
Player.Hand.{CardName}.{subtype}         # GOBLIN, ELF, …
Player.Hand.{CardName}.Keyword.{kw}      # FLYING, TRAMPLE, …
Player.Hand.{CardName}.ManaValue:{n}
Player.Battlefield.{CardName}.Card
Player.Battlefield.{CardName}.Permanent
Player.Battlefield.{CardName}.{type}_dynamic
Player.Battlefield.{CardName}.Tapped
Player.Battlefield.{CardName}.SummoningSick
Player.Battlefield.{CardName}.Power:{bucket}
Player.Battlefield.{CardName}.Toughness:{bucket}
Player.Battlefield.{CardName}.Damage:{n}
Player.Battlefield.{CardName}.Counter.{name}:{n}
Player.Graveyard.{CardName}.Card
Player.Graveyard.{CardName}.{type}
Opponent.CardsInHand:{n}                 # opponent hand is hidden
Opponent.LibraryCount:{bucket}
Opponent.{...}                           # same as Player for visible zones
Stack.{SpellName}.OnStack
Stack.{SpellName}.IsController           # if acting player controls it
```

Numeric values are bucketed to keep the vocabulary manageable:
- Life: 0, 1-5, 6-10, 11-20, 21+
- P/T: capped at 10 (e.g. "Power:10" for anything ≥10)
- Library: 0, 1-5, 6-10, 11-20, 21+
- Mana: exact (mana pools rarely exceed 10)

#### `action_encoder.py` — `LegalActionView` → (head, slot)

**Source of truth: `ActionEncoder.java`**

The slot formula replicates Java's `String.hashCode()` — not MurmurHash or any other scheme.
This is critical for compatibility with any XMage-generated training data.

```python
# Java String.hashCode() — must match exactly
def java_hash(s: str) -> int:
    h = 0
    for c in s:
        h = (31 * h + ord(c)) & 0xFFFFFFFF
    if h >= 0x80000000:
        h -= 0x100000000   # convert to signed 32-bit
    return h

def hash_slot(name: str) -> int:
    """Slots 1–127. Slot 0 is reserved for Pass/StopChoosing."""
    return abs(java_hash(name)) % 127 + 1
```

**Priority head slot assignment** (same for player and opponent maps):

| Canonical string | Slot | Notes |
|---|---|---|
| `"Pass"` | `0` | Always slot 0 — fully reserved |
| `"{T}: Add {B}"` | `1` | Argentum format — no trailing period |
| `"{T}: Add {G}"` | `2` | (`AddManaEffect.description` = `"Add {R}"`, `AbilityCost.Tap.description` = `"{T}"`) |
| `"{T}: Add {R}"` | `3` | |
| `"{T}: Add {U}"` | `4` | |
| `"{T}: Add {W}"` | `5` | |
| `"{T}: Add {C}"` | `6` | |
| anything else | `abs(java_hash(name)) % 127 + 1` | May collide with slots 1–6 in theory |

**Note:** `ActionEncoder.java` hardcodes `"{T}: Add {R}."` with a trailing period — Argentum produces
`"{T}: Add {R}"` without one (`AddManaEffect.description` omits it). Since we are training purely on
Argentum data we use Argentum's format. Cross-compatibility with XMage training data would require
normalizing both to a common format.

**Target head slot assignment**:

| Canonical string | Slot |
|---|---|
| `"Stop Choosing"` | `0` |
| `"PlayerA"` | `1` |
| `"PlayerB"` | `2` |
| anything else | `abs(java_hash(name)) % 127 + 1` |

**ActionType values** (from `ActionEncoder.ActionType` enum ordinals):

```python
ACTION_TYPE_PRIORITY      = 0   # PRIORITY
ACTION_TYPE_CHOOSE_NUM    = 1   # CHOOSE_NUM   (not currently trained)
ACTION_TYPE_BLANK         = 2   # BLANK        (not currently trained)
ACTION_TYPE_CHOOSE_TARGET = 3   # CHOOSE_TARGET
ACTION_TYPE_MAKE_CHOICE   = 4   # MAKE_CHOICE  (not currently trained)
ACTION_TYPE_CHOOSE_USE    = 5   # CHOOSE_USE   — binary decisions
```

**Full implementation**:

```python
# Hardcoded slots — Argentum format (no trailing period on mana ability descriptions)
# ActionEncoder.java uses "{T}: Add {R}." but Argentum's AddManaEffect produces "{T}: Add {R}"
_PRIORITY_FIXED = {
    "Pass": 0,
    "{T}: Add {B}": 1,
    "{T}: Add {G}": 2,
    "{T}: Add {R}": 3,
    "{T}: Add {U}": 4,
    "{T}: Add {W}": 5,
    "{T}: Add {C}": 6,
}
_TARGET_FIXED = {
    "Stop Choosing": 0,
    "PlayerA": 1,
    "PlayerB": 2,
}

def _java_hash(s: str) -> int:
    h = 0
    for c in s:
        h = (31 * h + ord(c)) & 0xFFFFFFFF
    return h - 0x100000000 if h >= 0x80000000 else h

def _hash_slot(name: str) -> int:
    return abs(_java_hash(name)) % 127 + 1

def priority_slot(canonical: str) -> int:
    return _PRIORITY_FIXED.get(canonical, _hash_slot(canonical))

def target_slot(canonical: str) -> int:
    return _TARGET_FIXED.get(canonical, _hash_slot(canonical))

def classify(kind: str, decision_kind: str | None) -> tuple[str, int]:
    """Returns (head_name, action_type_int)."""
    if decision_kind == "CHOOSE_TARGETS":
        return "target", ACTION_TYPE_CHOOSE_TARGET
    if decision_kind == "YES_NO":
        return "binary", ACTION_TYPE_CHOOSE_USE
    if kind in ("DeclareAttackers", "DeclareBlockers"):
        return "binary", ACTION_TYPE_CHOOSE_USE
    return "priority", ACTION_TYPE_PRIORITY

def canonical_priority(action: dict, obs: dict, perspective_id: str) -> str:
    """
    Canonical string for priority actions. Must match ability.toString() from XMage
    as closely as possible for cross-compatibility.
    """
    kind = action["kind"]
    if kind == "PassPriority":
        return "Pass"
    if kind == "ActivateAbility" and action.get("isManaAbility"):
        # Mana abilities: use manaCost string to match "{T}: Add {R}." format
        cost = action.get("manaCost") or ""
        return f"{{T}}: Add {cost}."
    if kind in ("CastSpell", "ActivateAbility", "PlayLand"):
        return _clean(action.get("description", kind))
    if kind == "DeclareAttackers":
        return "DeclareAttackers"
    if kind == "DeclareBlockers":
        return "DeclareBlockers"
    if kind == "DECISION":
        return _clean(action.get("description", ""))
    return _clean(action.get("description", kind))

def canonical_target(target_id: str, obs: dict, perspective_id: str) -> str:
    """Canonical string for a target entity."""
    players = {p["id"]: i for i, p in enumerate(obs.get("players", []))}
    if target_id in players:
        idx = players[target_id]
        return "PlayerA" if idx == 0 else "PlayerB"
    # Find card name
    for zone in obs.get("zones", []):
        for card in zone.get("cards", []):
            if card["entityId"] == target_id:
                return card["name"]
    return target_id  # fallback

def _clean(s: str) -> str:
    import re
    s = re.sub(r' \[[0-9a-f-]+\]', '', s)
    s = re.sub(r'<[^>]*>', '', s)
    return s.strip()
```

#### `mcts.py` — Python MCTS

Pure-Python PUCT tree search. At each node, calls the MageZero inference server (or uses uniform
policy in offline mode) to get priors + value, then runs N simulations via UCB selection.

The MCTS calls `client.step()` for leaf expansion (or `client.fork()` if gym-server supports it —
see note below). The tree root is always reset to the current env state.

```python
class MctsNode:
    def __init__(self, obs: dict, prior: float = 1.0):
        self.obs = obs
        self.prior = prior
        self.visits = 0
        self.value_sum = 0.0
        self.children: dict[int, MctsNode] = {}  # action_id → child

    @property
    def q(self):
        return self.value_sum / max(self.visits, 1)

class PythonMcts:
    def __init__(self, client: ArgentumClient, server_url: str | None,
                 simulations: int = 100, c_puct: float = 1.5,
                 offline: bool = False):
        self.client = client
        self.server_url = server_url
        self.simulations = simulations
        self.c_puct = c_puct
        self.offline = offline

    def select_action(self, env_id: str, obs: dict,
                      encoder: ArgentumStateEncoder,
                      action_encoder) -> tuple[int, list[float]]:
        """
        Run MCTS from the current state. Returns (chosen_action_id, visit_counts[128]).
        """
        root = MctsNode(obs)
        for _ in range(self.simulations):
            self._simulate(env_id, root, encoder, action_encoder)

        # Build visit count vector (128-dim policy label)
        legal = obs["legalActions"]
        pd = obs.get("pendingDecision")
        decision_kind = pd["kind"] if pd else None
        visit_vec = [0.0] * 128
        chosen_id, best_visits = legal[0]["actionId"], -1
        for action in legal:
            child = root.children.get(action["actionId"])
            v = child.visits if child else 0
            head, _ = action_encoder.classify(action["kind"], decision_kind)
            s = action_encoder.slot(action_encoder.canonical(action, obs), head)
            visit_vec[s] = float(v)
            if v > best_visits:
                best_visits, chosen_id = v, action["actionId"]

        return chosen_id, visit_vec
```

**Note on fork:** gym-server supports `POST /envs/{id}/fork` which creates a copy of the env at
constant cost (GameState is immutable). MCTS leaf expansion can fork, expand, evaluate, then
discard — avoiding re-running the game from the beginning for each simulation. This is a key
performance optimization; implement it in the second iteration.

#### `hdf5_writer.py` — write MageZero-compatible HDF5

Buffers states for one game, back-patches the result label, then appends to an HDF5 file.

```python
import h5py, numpy as np

ACTIONS_MAX = 128  # must match model.py

class HDF5Writer:
    def __init__(self, path: str):
        self.path = path
        self._buf_indices = []   # list of int32 arrays
        self._buf_rows = []      # list of float32[132] arrays
        self._game_rows = []     # pending (not yet outcome-labelled) for current game

    def begin_game(self):
        self._game_rows = []

    def record_step(self, feature_indices: list[int], policy_vec: list[float],
                    is_player: bool, action_type: int):
        row = np.zeros(ACTIONS_MAX + 4, dtype=np.float32)
        row[:ACTIONS_MAX] = policy_vec
        # row[128] = result label — filled in end_game()
        row[129] = 0.0            # stateScore (unused)
        row[130] = 1.0 if is_player else 0.0
        row[131] = float(action_type)
        self._game_rows.append((np.array(feature_indices, dtype=np.int32), row))

    def end_game(self, outcome: float):
        """outcome: +1.0 win, -1.0 loss, 0.0 draw — from player A's perspective."""
        for indices, row in self._game_rows:
            row[128] = outcome
            self._buf_indices.append(indices)
            self._buf_rows.append(row)
        self._game_rows = []

    def flush(self):
        if not self._buf_indices:
            return
        all_indices = np.concatenate(self._buf_indices)
        all_rows = np.stack(self._buf_rows, axis=0)
        offsets = np.zeros(len(self._buf_indices) + 1, dtype=np.int64)
        for i, idx in enumerate(self._buf_indices):
            offsets[i + 1] = offsets[i] + len(idx)

        with h5py.File(self.path, "a") as f:
            if "/indices" not in f:
                f.create_dataset("/indices", data=all_indices,
                                 maxshape=(None,), chunks=True)
                f.create_dataset("/offsets", data=offsets,
                                 maxshape=(None,), chunks=True)
                f.create_dataset("/row", data=all_rows,
                                 maxshape=(None, ACTIONS_MAX + 4), chunks=True)
            else:
                n_existing = f["/row"].shape[0]
                old_off_end = f["/offsets"][-1]
                f["/indices"].resize(f["/indices"].shape[0] + len(all_indices), axis=0)
                f["/indices"][-len(all_indices):] = all_indices
                adjusted_offsets = offsets[1:] + old_off_end
                f["/offsets"].resize(f["/offsets"].shape[0] + len(adjusted_offsets), axis=0)
                f["/offsets"][-len(adjusted_offsets):] = adjusted_offsets
                f["/row"].resize(f["/row"].shape[0] + len(all_rows), axis=0)
                f["/row"][-len(all_rows):] = all_rows
        self._buf_indices = []
        self._buf_rows = []
```

#### `collect.py` — generation runner

Replaces the XMage JVM launch. Plays N games, writes HDF5, exits.

```python
# python -m magezero.argentum.collect --games 200 --deck mono-red --version 1 --offline

def run_games(games: int, deck_config: dict, output_path: str,
              feature_map_path: str, server_url: str | None, offline: bool):
    fm = FeatureMap(feature_map_path)
    client = ArgentumClient()
    encoder = ArgentumStateEncoder(fm)
    ae = ArgentumActionEncoder()
    writer = HDF5Writer(output_path)
    mcts = PythonMcts(client, server_url, simulations=200, offline=offline)

    for game_num in range(games):
        env_id, obs = client.create_env(deck_config)
        writer.begin_game()
        is_player_a = (obs["agentToAct"] == obs["perspectivePlayerId"])

        while not obs.get("terminated"):
            acting = obs["agentToAct"]
            perspective = obs["perspectivePlayerId"]
            is_player = (acting == perspective)

            features = sorted(encoder.encode(obs))
            pd = obs.get("pendingDecision")
            decision_kind = pd["kind"] if pd else None
            head, action_type = ae.classify(
                obs["legalActions"][0]["kind"] if obs["legalActions"] else "PassPriority",
                decision_kind
            )

            action_id, visits = mcts.select_action(env_id, obs, encoder, ae)

            writer.record_step(features, visits, is_player, action_type)
            obs = client.step(env_id, action_id)

        winner = obs.get("winnerId")
        perspective = obs.get("perspectivePlayerId") or obs["players"][0]["id"]
        outcome = 1.0 if winner == perspective else (-1.0 if winner else 0.0)
        writer.end_game(outcome)
        client.dispose([env_id])

        if (game_num + 1) % 10 == 0:
            writer.flush()
            fm.save()
            print(f"[{game_num+1}/{games}] outcome={outcome:+.0f}")

    writer.flush()
    fm.save()
```

---

## FeatureMap

The FeatureMap assigns stable integers to feature strings. `GLOBAL_MAX = 2_000_000` in model.py —
feature strings must hash into this range.

**Implementation:** a dict `str → int` backed by a JSON file. On first encounter, assigns
`len(map)`. Saved and loaded between sessions. Must never reassign existing strings.

```python
class FeatureMap:
    def __init__(self, path: str):
        self.path = path
        self._map: dict[str, int] = {}
        if os.path.exists(path):
            self.load()

    def id(self, feature: str) -> int:
        if feature not in self._map:
            n = len(self._map)
            if n >= 2_000_000:
                raise OverflowError("FeatureMap full (2M limit)")
            self._map[feature] = n
        return self._map[feature]

    def save(self):
        with open(self.path, "w") as f:
            json.dump(self._map, f)

    def load(self):
        with open(self.path) as f:
            self._map = json.load(f)
```

One FeatureMap per featurizer version, shared across all agents and training runs. See
[`magezero-training.md`](../magezero-training.md) for the full rationale.

Default path: `data/features/argentum-v1.json`.

---

## Running the first generation

### Prerequisites

```bash
# 1. Start gym-server
just gym-server           # listens on localhost:8090

# 2. Install Python dependencies (MageZero/requirements.txt + new ones)
pip install -r MageZero/requirements.txt
pip install mmh3 h5py pyroaring waitress flask msgpack

# 3. Verify gym-server connectivity
python -c "
import requests
r = requests.post('http://localhost:8090/envs', json={
  'players': [
    {'name':'P1','deck':{'type':'Explicit','cards':{'Mountain':17,'Raging Goblin':3}}},
    {'name':'P2','deck':{'type':'Explicit','cards':{'Mountain':17,'Raging Goblin':3}}}
  ], 'skipMulligans': True
})
print('OK:', r.json()['envId'])
"
```

### Step 1 — Generation 0: offline MCTS (no model)

```bash
cd MageZero
python -m magezero.argentum.collect \
  --games 200 \
  --deck mono-red \
  --version 1 \
  --offline \
  --gym-url http://localhost:8090 \
  --output data/mono-red/ver1/testing/session1_mono-red_vs_mono-red.hdf5 \
  --feature-map data/features/argentum-v1.json
```

`--offline` uses uniform priors (no inference server). Equivalent to XMage's `offline_mode: true`.

### Step 2 — Train bootstrap model (50 epochs)

```bash
cd MageZero
python src/magezero/train.py \
  --deck mono-red \
  --version 1 \
  --epochs 50
# writes: models/mono-red/ver1/model.pt.gz + ignore.roar
```

### Step 3 — Start inference server

```bash
cd MageZero
python src/magezero/server.py \
  --deck mono-red \
  --version 1 \
  --port 50052
```

### Step 4 — Generation 1: self-play with model

```bash
cd MageZero
python -m magezero.argentum.collect \
  --games 200 \
  --deck mono-red \
  --version 1 \
  --gym-url http://localhost:8090 \
  --server-url http://localhost:50052 \
  --output data/mono-red/ver1/testing/session2_mono-red_vs_mono-red.hdf5 \
  --feature-map data/features/argentum-v1.json
```

### Step 5 — Train from checkpoint

```bash
cd MageZero
python src/magezero/train.py \
  --deck mono-red \
  --version 1 \
  --epochs 10 \
  --checkpoint
```

---

## File summary

### Argentum Engine — new files

| File | Purpose |
|---|---|
| `gym-server/.../service/HeuristicService.kt` | Runs engine AI, returns chosen action ID |
| `gym-server/.../dto/GymDtos.kt` | Add `HeuristicStepResult` |
| `gym-server/.../controller/EnvController.kt` | Add `POST /{id}/heuristic-step` |

### MageZero Python — new files

| File | Purpose |
|---|---|
| `MageZero/src/magezero/argentum/__init__.py` | Package marker |
| `MageZero/src/magezero/argentum/client.py` | gym-server HTTP client |
| `MageZero/src/magezero/argentum/encoder.py` | TrainingObservation → sparse indices |
| `MageZero/src/magezero/argentum/action_encoder.py` | LegalAction → (head, slot) |
| `MageZero/src/magezero/argentum/mcts.py` | Pure-Python PUCT tree search |
| `MageZero/src/magezero/argentum/hdf5_writer.py` | Writes MageZero-compatible HDF5 |
| `MageZero/src/magezero/argentum/feature_map.py` | String → int mapping, JSON-persisted |
| `MageZero/src/magezero/argentum/collect.py` | CLI entry point: run games, write HDF5 |

### MageZero Python — unchanged

`train.py`, `dataset.py`, `server.py`, `model.py`, `test.py`, `runner.py` (partially).

---

## Open questions (need ActionEncoder.java)

1. **Exact slot assignment for XMage actions**: The `ActionEncoder.java` will show the canonical
   string format and any special cases (X spells, kicked spells, multi-modal). We need to match
   this so checkpoints trained on XMage data are compatible with Argentum data, if desired.

2. **`CHOOSE_USE` definition**: `ActionType.CHOOSE_USE = 5` is used for attacker/blocker
   decisions in XMage. Argentum's `DeclareAttackers`/`DeclareBlockers` actions need to map to
   the same head. Confirm the exact binary semantics (yes/no per creature? or a batch action?).

3. **`see_opponent_hand: true`** in `game.yml`: XMage runs with opponent hand visible during
   MCTS simulations. Argentum's `revealAll` flag on `POST /envs` provides the same. Decide
   whether to enable `revealAll` for training (simpler, but leaks information) or keep
   information hiding (more realistic, matches tournament play).

4. **Behaviour cloning pre-training**: The plan above uses offline MCTS (uniform policy) for
   generation 0. Alternatively, `heuristic-step` can drive generation 0 with the engine AI's
   choices as direct policy labels, which likely produces a stronger bootstrap than uniform MCTS.
   This adds a behaviour cloning stage before self-play — worth doing if generation 0 quality
   is poor.
