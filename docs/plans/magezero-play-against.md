# Play Against MageZero — Implementation Plan

Enable a human to play against the trained MageZero AI through the existing
web client, using the same interface as playing against the heuristic or LLM AI.

---

## Architecture

```
Browser (human player)
  ↕ WebSocket
game-server
  → MageZeroAiPlayerController (Kotlin)
      → POST /decide
  → agent_service.py (Python, new)
      → ClientStateEncoder (adapted encoder.py)
      → POST /evaluate (msgpack)
  → server.py (MageZero inference server, existing)
      → model.pt.gz
```

The agent service is a thin Flask process that runs alongside the inference
server. It translates the game-server's `ClientGameState` JSON into sparse
feature indices, calls the inference server, and returns the chosen action.

---

## Step 1 — Python agent service

**File:** `MageZero/src/magezero/argentum/agent_service.py`

A Flask HTTP service that the Kotlin controller calls for every AI decision.

### Endpoint: `POST /decide`

Request body (JSON):
```json
{
  "state": { ...ClientGameState... },
  "legalActions": [
    { "actionId": "cast-spell-abc", "kind": "CastSpell",
      "description": "Cast Malcolm, Alluring Scoundrel {1}{U}",
      "affordable": true, "isManaAbility": false }
  ],
  "pendingDecision": null,
  "playerId": "P1"
}
```

Response body (JSON):
```json
{ "actionId": "cast-spell-abc" }
```

### Logic

1. Extract `state`, `legalActions`, `pendingDecision`, `playerId` from request.
2. Encode state via `ClientStateEncoder` (see Step 2).
3. Encode legal actions via adapted `action_encoder.encode_actions()`.
4. Call inference server (`InferenceClient.evaluate(indices)`).
5. Select action with highest policy logit for the correct head.
6. Return `{ "actionId": chosen_action_id }`.

### Fallback

If the inference server is unreachable or returns an error, fall back to
choosing the first affordable non-pass action (or pass if none).

---

## Step 2 — `ClientStateEncoder`

**File:** `MageZero/src/magezero/argentum/client_encoder.py`

Adapts `encoder.py` to read `ClientGameState` format (game-server's web client
format) instead of `TrainingObservation` format (gym-server format).

### Field mapping (verified against `ClientDTO.kt`)

| TrainingObservation | ClientGameState |
|---|---|
| `obs["step"]` | `state["currentStep"]` |
| `obs["phase"]` | `state["currentPhase"]` |
| `obs["perspectivePlayerId"]` | `state["viewingPlayerId"]` |
| `obs["agentToAct"]` | `state["priorityPlayerId"]` |
| `obs["players"][i]["lifeTotal"]` | `state["players"][i]["life"]` |
| `obs["players"][i]["handSize"]` | `state["players"][i]["handSize"]` |
| `obs["players"][i]["librarySize"]` | `state["players"][i]["librarySize"]` |
| `obs["players"][i]["isActive"]` | `player["playerId"] == state["activePlayerId"]` |
| `obs["players"][i]["hasPriority"]` | `player["playerId"] == state["priorityPlayerId"]` |
| `obs["players"][i]["manaPool"][color]` | `state["players"][i]["manaPool"]["white/blue/…"]` (same shape, may be null for opponent) |
| card `types` | `card["cardTypes"]` (Set\<String\>) |
| card `subtypes` | `card["subtypes"]` (Set\<String\>) |
| card `colors` | `card["colors"]` (Set\<Color\>, serialized as strings) |
| card `keywords` | `card["keywords"]` (Set\<Keyword\>, serialized as strings) |
| card `tapped` | `card["isTapped"]` |
| card `summoningSick` | `card["hasSummoningSickness"]` |
| card `damageMarked` | `card["damage"]` (nullable) |
| card `counters` | `card["counters"]` (Map\<CounterType, Int\>) |
| zones with embedded cards | `state["zones"]` has `cardIds`; look up each in `state["cards"]` map |
| stack items | cards in `state["zones"]` where `zoneId.zoneType == "STACK"` |

Card fields inside zones are the same shape in both formats (name, types,
subtypes, colors, keywords, power, toughness, tapped, etc.) — the zone view
structure is shared via `ClientCard`.

### Decision type

`ClientGameState` doesn't embed the pending decision in the state object —
the Kotlin controller passes it separately. The encoder receives it as a
parameter and adds the decision-type feature.

---

## Step 3 — `MageZeroAiPlayerController` (Kotlin)

**File:** `ai/src/main/kotlin/com/wingedsheep/ai/magezero/MageZeroAiPlayerController.kt`

Implements `AiPlayerController`. For every decision:

1. Serialize `ClientGameState` + `List<LegalActionInfo>` + `PendingDecision?`
   to JSON.
2. POST to `http://localhost:5005/decide` (configurable via `AiConfig`).
3. Parse the returned `actionId` string.
4. Find the matching `LegalActionInfo` and return `ActionResponse.SubmitAction`.
5. On error or timeout: delegate to the engine heuristic fallback.

### Configuration

```yaml
game:
  ai:
    magezero-url: http://localhost:5005
```

Enabled when `mode=magezero` in `GameProperties.ai`.

---

## Step 4 — Wire into `AiGameManager`

**File:** `game-server/.../ai/AiGameManager.kt`

Add a `magezero` mode alongside `engine` and `llm`:

```kotlin
"magezero" -> MageZeroAiPlayerController(
    agentUrl = gameProperties.ai.magezeroUrl,
    fallback  = EngineAiPlayerController(cardRegistry, playerId)
)
```

---

## Step 5 — UI: agent selection

**File:** `web-client/src/...` (QuickGame or lobby flow)

Add "MageZero" to the AI opponent dropdown. Sends `mode=magezero` to the
server when starting a game. Small change — only if `magezero-url` is
configured and the service is reachable.

---

## Running the full stack

```bash
# Terminal 1 — gym-server (not needed for play, only for training)
just gym-server

# Terminal 2 — game-server
just server

# Terminal 3 — MageZero inference server
cd MageZero
./venv/bin/python3 src/magezero/server.py --deck uwtempo --version 1 --port 50052

# Terminal 4 — agent service (new)
cd MageZero
./venv/bin/python3 -m magezero.argentum.agent_service --port 5005 \
  --server-url http://127.0.0.1:50052 \
  --feature-map data/features/argentum-v1.json

# Browser
open http://localhost:5173
# Start a quick game, select "MageZero" as opponent, pick UWTempo deck
```

---

## Files to create / modify

### New — Python

| File | Purpose |
|---|---|
| `MageZero/src/magezero/argentum/client_encoder.py` | Encode `ClientGameState` → sparse indices |
| `MageZero/src/magezero/argentum/agent_service.py` | Flask `/decide` endpoint |

### New — Kotlin

| File | Purpose |
|---|---|
| `ai/.../magezero/MageZeroAiPlayerController.kt` | Calls agent service, returns action |

### Modified — Kotlin

| File | Change |
|---|---|
| `game-server/.../ai/AiGameManager.kt` | Add `magezero` mode |
| `game-server/.../config/GameProperties.kt` | Add `magezeroUrl` property |
| `game-server/.../config/application.yml` | Add `game.ai.magezero-url` |

### Modified — Web client

| File | Change |
|---|---|
| Quick game / lobby UI | Add MageZero to opponent dropdown |

---

## Open questions

1. **`ClientGameState` field names**: need to verify exact field names against
   `ClientGameState.kt` and `ClientCard.kt` before writing `ClientStateEncoder`.
   The mapping table above is approximate.

2. **Legal action format**: `LegalActionInfo` (game-server) vs `LegalActionView`
   (gym-server) have different field names. The agent service receives whichever
   the Kotlin controller serializes — confirm which fields to include.

3. **Deck constraint**: the current model is trained only on UWTempo vs UWTempo.
   The AI will play poorly with other decks (it's never seen their cards as
   actions). For now, only offer MageZero as opponent when the human also plays
   UWTempo.
