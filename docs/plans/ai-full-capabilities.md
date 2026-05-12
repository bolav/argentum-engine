# AI Full Capabilities — Implementation Plan

This plan describes everything needed to take Argentum Engine from its current AI foundation to a full
AI platform: weight-based self-play training compatible with MageZero, human vs AI in all formats, live
AI vs AI tournaments, persistent leaderboards, and an external agent API for bots and LLMs.

---

## Current state

| Capability | Status | Location |
|---|---|---|
| Human vs Engine AI (quick game) | Working | `AiGameManager`, `EngineAiPlayerController` |
| Human vs LLM AI | Working | `LlmAiPlayerController` |
| AI vs AI batch runner | Working (CLI only) | `AiMatchupRunner` |
| AI sealed tournament (dev endpoints only) | Working | `AiTournamentController` |
| MCTS / AlphaZero infrastructure | Skeleton | `gym-trainer` modules |
| LLM draft + deck building | Working | `LlmAiPlayerController` |
| Gym HTTP server for Python trainers | Working | `gym-server` |

Gaps fall into four buckets:

1. **Training**: `StructuralStateFeaturizer` and `DynamicSlotActionFeaturizer` are proof-of-concept stubs;
   no MageZero-compatible sparse encoding or stable multi-head policy.
2. **External interface**: No API for external bots or LLMs to _play_ games (only evaluate positions via gym-server).
3. **Leaderboard**: No persistent agent registry or ELO ratings across sessions.
4. **UI/format coverage**: AI tournaments are dev-only; not all formats support AI opponents.

---

## Phases

### Phase 1 — MageZero-compatible training stack

**Goal:** produce `(state, π, z)` self-play triples that a MageZero-style PyTorch EmbeddingBag network
can train on directly.

Details: [`docs/magezero-training.md`](../magezero-training.md)

#### 1a. `FeatureMap` — stable string → int index

A thread-safe, disk-persistent map that assigns integer indices to feature strings. Once a string is
assigned an index it never changes. New features can be added at any time; the map grows monotonically.

```kotlin
// gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/makezero/FeatureMap.kt
class FeatureMap(val path: Path? = null) {
    fun id(feature: String): Int          // assign or retrieve index
    fun size(): Int                        // number of known features
    fun save()                             // persist to JSON
    fun load()                             // restore from JSON
    companion object { fun load(path: Path): FeatureMap }
}
```

The map is a **vocabulary tied to the featurizer version**, not to any individual trained agent.
All self-play runs and model checkpoints that use `MageZeroStateFeaturizer` v1 share one file.
A checkpoint's `meta.json` records which map it was trained against and what `featureDim` the
embedding layer was initialized with. See [`magezero-training.md`](../magezero-training.md) for
the full layout.

Default path: `data/features/magezero-v1.json` (configurable via `gym.trainer.feature-map-path`).

**File:** `gym-trainer/.../makezero/FeatureMap.kt`
**Test:** `FeatureMapTest` — stability across save/reload, thread safety.

#### 1b. `SparseFeatures` — the output type

```kotlin
@Serializable
@JvmInline
value class SparseFeatures(val indices: IntArray)
```

Sorted array of active feature indices. Directly ingestible by PyTorch
`EmbeddingBag(mode="sum", sparse=True)`.

**File:** `gym-trainer/.../makezero/SparseFeatures.kt`

#### 1c. `MageZeroStateFeaturizer` — full game-state encoder

Implements `StateFeaturizer<SparseFeatures>`. Mirrors MageZero's `StateEncoder.processState()` for
Argentum's `GameState`. Hierarchical namespace → feature string → `FeatureMap.id()` → sorted index set.

Top-level namespace hierarchy:

```
{turnStep}                              // UPKEEP, MAIN_PHASE_ONE, COMBAT_DECLARE_ATTACKERS, …
{decisionType}                          // PRIORITY, CHOOSE_TARGET, YES_NO, CHOOSE_MODE, …
Player.{…}                             // acting player's perspective
Opponent.{…}                           // opponent's perspective
Stack.{SpellName}.{…}                  // top-of-stack first
Exile.{ZoneName}.{CardName}.{…}
```

Player/Opponent sub-namespace:

```
Player.LifeTotal:{N}
Player.LibraryCount:{N}
Player.CardsInHand:{N}           (opponent only, when hand is hidden)
Player.CanPlayLand
Player.IsActivePlayer
Player.IsDecisionPlayer
Player.ManaPool.{W|U|B|R|G|C}:{N}
Player.Hand.{CardName}.{…}       (acting player only)
Player.Battlefield.{CardName}.{…}
Player.Graveyard.{CardName}.{…}
```

Per-card features (in any zone):
```
{ns}.Card
{ns}.Permanent                   (if permanent)
{ns}.{CardType}                  (Creature, Instant, Sorcery, …)
{ns}.{Color}Card                 (RedCard, WhiteCard, … ColorlessCard, MultiColored)
{ns}.{SubType}                   (Goblin, Elf, Merfolk, …)
{ns}.ManaValue:{N}
{ns}.{ManaPip}                   ({W}, {2}, {R}{G}, …)
```

Per-permanent additional features:
```
{ns}.Tapped
{ns}.SummoningSick
{ns}.CanAttack
{ns}.CanBlock
{ns}.Attacking
{ns}.Power:{N}                   (post-layers via projected state)
{ns}.Toughness:{N}               (post-layers via projected state)
{ns}.Damage:{N}
{ns}.Counter.{Name}:{N}
{ns}.Keyword.{Name}              (Flying, Trample, Haste, …)
{ns}.Ability.{ruleText}          (activated / triggered / static abilities)
```

**Key constraint:** All battlefield queries use `predicateEvaluator.matchesWithProjection()` and
`projected.isCreature()`, `projected.getPower()` etc., never base-state type checks.
See CLAUDE.md "Projected state for battlefield filters."

**Numeric features** are encoded as `{key}:{value}` strings so they hash to per-value buckets,
matching MageZero's approach. The same key at different values gets different indices.

**File:** `gym-trainer/.../makezero/MageZeroStateFeaturizer.kt`
**Test:** `MageZeroStateFeaturizerTest` — determinism across identical states, no duplicate indices,
projected-state calls used for creature checks.

#### 1d. `MageZeroActionFeaturizer` — multi-head stable action encoding

Four policy heads matching MageZero's architecture:

| Head | Size | Actions |
|---|---|---|
| `priority` | 4096 | `CastSpell`, `ActivateAbility`, `PassPriority`, `PlayLand`, `DeclareAttackers`, `DeclareBlockers` |
| `target` | 2048 | `ChooseTargetsDecision` responses; blocker assignments |
| `binary` | 256 | `YesNoDecision`, single-select `SelectCardsDecision`, single-mode `ChooseModeDecision` |
| `modal` | 1024 | Multi-mode `ChooseModeDecision`, multi-select `SelectCardsDecision`, `DistributeDecision` |

Slot assignment: `murmur3(canonicalString(action)) mod headSize`. Stable because it hashes a
deterministic description string, not a JVM `hashCode()`.

Canonical string rules (same as MageZero's `cleanString`):
- Remove all UUIDs (replaced with the card/player name from context)
- Remove HTML/XML tags
- Strip whitespace, lowercase

**File:** `gym-trainer/.../makezero/MageZeroActionFeaturizer.kt`
**Test:** `MageZeroActionFeaturizerTest` — no collisions in a 100-game sample, head assignment correct
per decision type.

#### 1e. `MageZeroSelfPlaySink` — training data writer

Emits one JSONL line per decision step. `endGame()` back-patches the `outcome` field on all buffered
rows before flushing — same as `JsonlSelfPlaySink` but with the MageZero-compatible schema:

```json
{
  "stateFeatures": [12, 45, 789, 1203],
  "head": "priority",
  "legalSlots": [{"head": "priority", "slot": 3}, ...],
  "visits": [0, 0, 42, 0, 18, 0, ...],
  "mctsValue": 0.62,
  "outcome": 1.0
}
```

- `stateFeatures`: sorted sparse index array from `MageZeroStateFeaturizer`
- `visits`: dense array of MCTS visit counts indexed by slot within the used head
- `outcome`: `+1.0` (win), `-1.0` (loss), `0.0` (draw/truncation), from acting player's perspective

**File:** `gym-trainer/.../makezero/MageZeroSelfPlaySink.kt`
**Test:** outcome back-patching, JSONL is valid JSON, indices are sorted.

#### 1f. `RemoteHttpEvaluator` extension for sparse features

Add `RemoteHttpEvaluator.forSparse(url)` factory. The wire request sends `stateFeatures` as an
`IntArray` instead of a serialized `Map<String, Float>`:

```json
{
  "stateFeatures": [12, 45, 789],
  "legalSlots": [{"head": "priority", "slot": 3}, ...],
  "decisionType": "PRIORITY",
  "playerId": "..."
}
```

Response format is unchanged (`priors` + `value`).

**File:** `gym-trainer/.../defaults/RemoteHttpEvaluator.kt` (add factory method)

#### 1g. End-to-end validation test

A `SelfPlayLoopTest` variant that runs 5 games with:
- `MageZeroStateFeaturizer` + shared `FeatureMap`
- `MageZeroActionFeaturizer`
- `HeuristicEvaluator`
- `MageZeroSelfPlaySink` to a temp file

Assertions: JSONL lines are valid, indices are sorted and within `FeatureMap.size()`, feature map is
stable after save/reload.

---

### Phase 2 — External agent API

**Goal:** an HTTP + WebSocket API so external bots, Python scripts, and remote LLMs can _play_ full
games, not just evaluate positions.

Details: [`docs/external-agent-api.md`](../external-agent-api.md)

#### 2a. `ExternalAgentController` — game lifecycle REST

```
POST /api/agent/games
  body: { deckP1: {name→count}, deckP2: {name→count}, format: "STANDARD" }
  → { gameId, tokenP1, tokenP2 }

GET /api/agent/games/{gameId}/state
  → current ClientGameState (same JSON as human client, masked for P1)

POST /api/agent/games/{gameId}/actions
  body: { token: "...", actionId: 42 }        // simple actions
  body: { token: "...", decision: {...} }      // structured decisions

DELETE /api/agent/games/{gameId}
```

**File:** `game-server/.../controller/ExternalAgentController.kt`

#### 2b. `ExternalAgentWebSocket` — streaming state updates

```
WS /api/agent/ws/{gameId}?token={tokenP1}
  ← { type: "StateUpdate", state: {...}, legalActions: [...], pendingDecision: {...} }
  → { type: "SubmitAction", actionId: 42 }
  → { type: "SubmitDecision", decision: {...} }
  ← { type: "GameOver", winner: "P1", finalState: {...} }
```

The WebSocket emits the identical `StateUpdate` JSON the human client receives (minus UI-only fields).
External bots use the same `actionId` from `legalActions` the human client uses.

**File:** `game-server/.../ai/ExternalAgentWebSocket.kt`

#### 2c. `ExternalAgentPlayerSession`

Extends `PlayerSession`. Buffers `StateUpdate` messages and fulfils pending action futures when the
external agent submits. Integrates with `GamePlayHandler` transparently — the game engine sees it as
any other player session.

**File:** `game-server/.../session/ExternalAgentPlayerSession.kt`

#### 2d. Security and rate limiting

- Tokens are short-lived UUIDs generated at game creation, not reusable across games.
- `game.external-agent.enabled` property gates the entire API (default: `false`).
- Configurable per-game action timeout (default 30 s); timeout fires `PassPriority`.

---

### Phase 3 — Persistent AI agent registry and leaderboards

**Goal:** named AI agents with persistent ELO ratings that accumulate across all game sessions and
formats.

#### 3a. Data model

```kotlin
data class AiAgentRecord(
    val id: String,                  // "engine-v3", "claude-sonnet-4-6", "makezero-v1"
    val displayName: String,
    val type: AgentType,             // ENGINE, LLM, MAKEZERO, EXTERNAL
    val config: JsonObject,          // model name, endpoint URL, etc.
    val elo: Double = 1000.0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val createdAt: Instant,
    val lastPlayedAt: Instant?
)

data class AgentMatchResult(
    val id: UUID,
    val agentAId: String,
    val agentBId: String,
    val winnerId: String?,           // null = draw
    val format: String,
    val deckA: String,               // deck name or hash
    val deckB: String,
    val turns: Int,
    val playedAt: Instant
)
```

Storage: JSON files under `data/agents/` for the initial version (no Spring Data dependency);
migrate to DB when seasonal league lands.

#### 3b. `AgentRegistry` service

```kotlin
@Service
class AgentRegistry {
    fun register(agent: AiAgentRecord)
    fun get(id: String): AiAgentRecord?
    fun all(): List<AiAgentRecord>
    fun recordResult(agentAId: String, agentBId: String, winnerId: String?)
    // ELO update: K=32, standard FIDE formula
}
```

**File:** `game-server/.../ai/AgentRegistry.kt`

#### 3c. Leaderboard REST API

```
GET /api/leaderboard
  → [{ id, displayName, elo, wins, losses, draws, winRate, lastPlayedAt }]
  sorted by elo desc

GET /api/leaderboard/{agentId}
  → agent detail + head-to-head breakdown vs each opponent

GET /api/leaderboard/{agentId}/history
  → [{ date, elo }] ELO over time for charting
```

**File:** `game-server/.../controller/LeaderboardController.kt`

#### 3d. Wire up ELO to existing AI matches

When `AiMatchupRunner` completes a game and both players are registered agents, call
`AgentRegistry.recordResult()`. Same for `AiGameManager` when a human beats or loses to an AI (track
each AI agent's record separately).

---

### Phase 4 — Live AI tournament (all formats, spectatable)

**Goal:** promote `AiTournamentController` from dev-only to production; add constructed format support;
display leaderboard impact.

#### 4a. Remove dev-endpoint gate from `AiTournamentController`

Replace `@ConditionalOnProperty(name = ["game.dev-endpoints.enabled"])` with a separate
`game.ai-tournament.enabled` property (default: `true`).

#### 4b. Constructed format AI tournament

Extend `AiTournamentController` to accept a `format: "CONSTRUCTED"` field. In constructed mode:
- `decks` list is required (one per player slot)
- Boosters are not generated
- `LobbyHandler.createAiTournamentWithFixedDecks()` already handles this; just wire the format field

#### 4c. `AiTournamentScheduler` (optional, background)

A background service that runs nightly self-play tournaments between registered agents, accumulates
training data, and updates ELO. Triggered by a `@Scheduled` Spring bean, configurable via
`game.ai-tournament.scheduled.cron`.

#### 4d. Tournament leaderboard UI integration

Add a "Leaderboard" tab to the web client that polls `GET /api/leaderboard` and renders a ranked table.
ELO badge shown on each agent's tournament entry.

---

### Phase 5 — Human vs AI format coverage and agent selection

**Goal:** let players choose which AI agent to face in any game format.

#### 5a. Agent selection in quick game flow

Extend `QuickGameController` to accept an `agentId` parameter. `AiGameManager` instantiates the
matching controller type (engine, LLM, external) based on `AiAgentRecord.type` and `config`.

#### 5b. Draft AI opponent

Verify `LlmAiPlayerController.chooseDraftPick()` works in all draft formats (Winston, Grid, Booster).
Add a fallback in `EngineAiPlayerController` for draft picks when LLM is not configured — use
`LimitedCardRater` to rank the pack and pick greedily.

#### 5c. Human vs AI in tournaments

Allow a tournament lobby to have a mix of human and AI players. `LobbyHandler.createAiTournament()`
already supports this partially; extend it to let a human join an otherwise-AI lobby via the standard
join flow.

---

## Dependency graph

```
Phase 1a (FeatureMap)
  └── Phase 1b (SparseFeatures)
        └── Phase 1c (MageZeroStateFeaturizer)
        └── Phase 1d (MageZeroActionFeaturizer)
              └── Phase 1e (MageZeroSelfPlaySink)
              └── Phase 1f (RemoteHttpEvaluator extension)
                    └── Phase 1g (end-to-end validation)

Phase 2a–2d (External agent API)    [independent, can run parallel to Phase 1]

Phase 3a–3b (Agent registry)
  └── Phase 3c (Leaderboard API)
  └── Phase 3d (Wire ELO to matches)

Phase 4a (Remove dev gate)          [depends on 3a–3b for ELO impact]
Phase 4b (Constructed tournament)   [depends on 4a]
Phase 4c (Scheduler)                [depends on 3a–3b, 1e]

Phase 5a–5c                         [depends on 3a, can run in parallel with 4]
```

---

## Files to create / modify (summary)

### New files — `gym-trainer`

| File | Phase |
|---|---|
| `.../makezero/FeatureMap.kt` | 1a |
| `.../makezero/SparseFeatures.kt` | 1b |
| `.../makezero/MageZeroStateFeaturizer.kt` | 1c |
| `.../makezero/MageZeroActionFeaturizer.kt` | 1d |
| `.../makezero/MageZeroSelfPlaySink.kt` | 1e |
| `test/.../makezero/FeatureMapTest.kt` | 1a |
| `test/.../makezero/MageZeroStateFeaturizerTest.kt` | 1c |
| `test/.../makezero/MageZeroActionFeaturizerTest.kt` | 1d |
| `test/.../makezero/MageZeroSelfPlayLoopTest.kt` | 1g |

### Modified files — `gym-trainer`

| File | Change |
|---|---|
| `.../defaults/RemoteHttpEvaluator.kt` | Add `forSparse()` factory |

### New files — `game-server`

| File | Phase |
|---|---|
| `.../controller/ExternalAgentController.kt` | 2a |
| `.../ai/ExternalAgentWebSocket.kt` | 2b |
| `.../session/ExternalAgentPlayerSession.kt` | 2c |
| `.../ai/AgentRegistry.kt` | 3b |
| `.../controller/LeaderboardController.kt` | 3c |
| `.../ai/AiTournamentScheduler.kt` | 4c |

### Modified files — `game-server`

| File | Change |
|---|---|
| `AiTournamentController.kt` | Remove dev gate, add constructed format |
| `AiGameManager.kt` | Wire ELO recording on game end |
| `AiMatchupRunner.kt` | Wire ELO recording on game end |
| `QuickGameController.kt` | Accept `agentId` parameter |
| `application.yml` | Add `game.external-agent.*`, `game.ai-tournament.*` properties |

### New files — `web-client`

| File | Phase |
|---|---|
| `src/components/Leaderboard.tsx` | 3c |
| `src/pages/LeaderboardPage.tsx` | 3c |

### New docs

| File | Content |
|---|---|
| `docs/makezero-training.md` | State encoding, action encoding, training pipeline |
| `docs/external-agent-api.md` | External agent HTTP + WebSocket API reference |

---

## Open questions

1. **FeatureMap location**: Resolved — one map per featurizer version, shared by all agents and
   self-play runs using that version. Default at `data/features/magezero-v1.json`. Model checkpoints
   record the map path and their trained `featureDim` in a `meta.json` sidecar.

2. **Head sizes**: The values in Phase 1d (4096 / 2048 / 256 / 1024) are estimates. They should be
   validated with a 1000-game sample to confirm the collision rate is below 1% per head.

3. **ELO K-factor**: K=32 is standard for new players. Consider dropping to K=16 after 30 games
   (provisional period) for more stable long-run ratings.

4. **External agent API auth**: For a local-only setup, UUID tokens are sufficient. For a future
   hosted service, these should be scoped bearer tokens with expiry.
