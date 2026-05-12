# External Agent API

An HTTP + WebSocket API that lets external bots, Python scripts, and remote LLMs play full games
against the Argentum Engine.

This is distinct from `:gym-server`, which is a training environment for RL agents (reset/step/fork
semantics). The external agent API is the **full game server protocol** — the same state updates and
action format the browser client uses — exposed over HTTP and WebSocket so non-browser agents can
connect.

---

## When to use which interface

| Interface | Use when |
|---|---|
| `:gym-server` | Training loop, MCTS, self-play; you want fast reset/step/fork; no human opponent |
| External Agent API | Playing a full game (vs human, vs another AI, in a tournament); you want game events, spectating, ELO recording |

---

## Enabling the API

Disabled by default. Enable in `application.yml`:

```yaml
game:
  external-agent:
    enabled: true
    action-timeout-seconds: 30   # how long to wait for an agent action before auto-passing
```

Or via environment variable: `GAME_EXTERNAL_AGENT_ENABLED=true`.

---

## Workflow

1. Create a game → receive `gameId` and two player tokens.
2. Connect both players via WebSocket (or leave P2 as an AI opponent).
3. Each turn: receive a `StateUpdate`, submit an action.
4. Receive `GameOver` when the game ends.

---

## REST API

### Create a game

```
POST /api/agent/games
Content-Type: application/json

{
  "deckP1": { "Mountain": 20, "Raging Goblin": 20 },
  "deckP2": { "Plains": 20, "Savannah Lions": 20 },
  "formatConfig": {
    "skipMulligans": false,
    "startingLife": 20,
    "maxTurns": 50
  },
  "p2AgentId": "engine-v3"   // optional: use a registered AI for P2 instead of an external agent
}
```

Response:

```json
{
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "tokenP1": "3f8a1c9d-...",
  "tokenP2": "7b2e4f0a-..."   // omitted if p2AgentId was specified
}
```

Tokens are single-use per game and expire when the game ends.

### Get current state (polling alternative to WebSocket)

```
GET /api/agent/games/{gameId}/state?token={token}
```

Returns the current `ClientGameState` masked for the token's player, plus `legalActions` and
`pendingDecision` (same structure as the WebSocket `StateUpdate` message).

### Submit an action (polling alternative to WebSocket)

```
POST /api/agent/games/{gameId}/actions
Content-Type: application/json

{
  "token": "3f8a1c9d-...",
  "actionId": 2
}
```

For structured decisions:

```
POST /api/agent/games/{gameId}/decisions
Content-Type: application/json

{
  "token": "3f8a1c9d-...",
  "decision": {
    "type": "YesNoResponse",
    "value": true
  }
}
```

Both return `{ "ok": true }` on success or `{ "error": "..." }` on failure.

### End a game

```
DELETE /api/agent/games/{gameId}?token={token}
```

Concedes the game for the token's player. Returns `204 No Content`.

---

## WebSocket API

### Connect

```
WS /api/agent/ws/{gameId}?token={token}
```

The server sends JSON messages. The client (bot) sends JSON messages.

Both players must connect within 60 seconds of game creation or the game is cancelled.

### Messages: server → agent

#### `StateUpdate`

Sent after every state change. The agent should only act when `legalActions` is non-empty and the
`actingPlayerId` matches the agent's player.

```json
{
  "type": "StateUpdate",
  "actingPlayerId": "P1",
  "state": {
    "viewingPlayerId": "P1",
    "players": [...],
    "cards": {...},
    "zones": [...],
    "turn": 3,
    "step": "MAIN_PHASE_ONE",
    "activePlayerId": "P1",
    "priorityPlayerId": "P1",
    "combat": null
  },
  "legalActions": [
    {
      "actionId": 0,
      "actionType": "PassPriority",
      "description": "Pass priority",
      "isAffordable": true,
      "isManaAbility": false
    },
    {
      "actionId": 1,
      "actionType": "PlayLand",
      "description": "Play Mountain",
      "cardId": "abc-123",
      "isAffordable": true
    },
    {
      "actionId": 2,
      "actionType": "CastSpell",
      "description": "Cast Raging Goblin {R}",
      "cardId": "def-456",
      "isAffordable": true,
      "requiresTargets": false
    }
  ],
  "pendingDecision": null
}
```

When `pendingDecision` is non-null, the agent must submit a decision response instead of an action.
See [Pending Decisions](#pending-decisions) below.

#### `GameOver`

```json
{
  "type": "GameOver",
  "winner": "P1",        // null for draw
  "reason": "LifeTotal", // "LifeTotal", "DeckOut", "MaxTurns", "Concede"
  "finalState": { ... }
}
```

After `GameOver` the WebSocket connection is closed by the server.

#### `Error`

```json
{
  "type": "Error",
  "message": "Action 99 is not a legal action in this state",
  "fatal": false
}
```

Non-fatal errors allow the agent to retry. Fatal errors close the connection.

### Messages: agent → server

#### `SubmitAction`

```json
{
  "type": "SubmitAction",
  "actionId": 2
}
```

`actionId` must match one of the `actionId` values in the most recent `legalActions` list.

#### `SubmitDecision`

When `pendingDecision` is non-null:

```json
{
  "type": "SubmitDecision",
  "decision": {
    "type": "YesNoResponse",
    "value": true
  }
}
```

See [Pending Decisions](#pending-decisions) for all decision types.

#### `Ping`

```json
{ "type": "Ping" }
```

Server responds with `{ "type": "Pong" }`. Use to keep the connection alive.

---

## Pending Decisions

When `pendingDecision` is non-null in a `StateUpdate`, the agent must submit a decision rather than
a standard action. The `type` field of the pending decision indicates the response format.

### `YesNoDecision`

```json
// pendingDecision
{
  "type": "YesNoDecision",
  "prompt": "Do you want to sacrifice Goblin Arsonist?",
  "playerId": "P1"
}

// response
{
  "type": "SubmitDecision",
  "decision": { "type": "YesNoResponse", "value": false }
}
```

### `ChooseTargetsDecision`

```json
// pendingDecision
{
  "type": "ChooseTargetsDecision",
  "prompt": "Choose a target for Lightning Bolt",
  "playerId": "P1",
  "requirements": [
    {
      "index": 0,
      "description": "target creature or player",
      "validTargets": ["P2", "perm-123", "perm-456"]
    }
  ]
}

// response
{
  "type": "SubmitDecision",
  "decision": {
    "type": "ChooseTargetsResponse",
    "targets": [
      { "type": "Player", "id": "P2" }
    ]
  }
}
```

Target `type` is one of `"Player"`, `"Permanent"`, or `"Spell"` (stack object).

### `SelectCardsDecision`

```json
// pendingDecision
{
  "type": "SelectCardsDecision",
  "prompt": "Choose cards to discard",
  "playerId": "P1",
  "minCards": 1,
  "maxCards": 2,
  "validCards": ["card-abc", "card-def", "card-ghi"]
}

// response
{
  "type": "SubmitDecision",
  "decision": {
    "type": "SelectCardsResponse",
    "cardIds": ["card-abc", "card-def"]
  }
}
```

### `ChooseModeDecision`

```json
// pendingDecision
{
  "type": "ChooseModeDecision",
  "prompt": "Choose one —",
  "playerId": "P1",
  "minModes": 1,
  "maxModes": 1,
  "modes": [
    { "index": 0, "description": "Draw two cards" },
    { "index": 1, "description": "Deal 3 damage to target creature" }
  ]
}

// response
{
  "type": "SubmitDecision",
  "decision": {
    "type": "ChooseModeResponse",
    "selectedModes": [1]
  }
}
```

### `DistributeDecision`

```json
// pendingDecision
{
  "type": "DistributeDecision",
  "prompt": "Distribute 3 damage among any number of targets",
  "playerId": "P1",
  "total": 3,
  "validTargets": ["perm-123", "perm-456", "P2"]
}

// response
{
  "type": "SubmitDecision",
  "decision": {
    "type": "DistributeResponse",
    "distribution": { "perm-123": 2, "P2": 1 }
  }
}
```

### `YesNoDecision` (shortcut via `legalActions`)

Simple yes/no decisions where both choices appear in `legalActions` do not require `SubmitDecision` —
submit `SubmitAction` with the matching `actionId`.

---

## Action timeout

If the agent does not submit an action within `action-timeout-seconds` (default: 30), the server
automatically submits the first legal action (usually `PassPriority`). A `Timeout` message is sent
before the auto-action:

```json
{ "type": "Timeout", "autoAction": "PassPriority" }
```

---

## Python example

```python
import asyncio, json, websockets, httpx

async def play_game():
    # Create game
    async with httpx.AsyncClient() as client:
        resp = await client.post("http://localhost:8080/api/agent/games", json={
            "deckP1": {"Mountain": 20, "Raging Goblin": 20},
            "deckP2": {"Plains": 20, "Savannah Lions": 20}
        })
        game = resp.json()

    game_id = game["gameId"]
    token = game["tokenP1"]

    # Connect WebSocket
    async with websockets.connect(
        f"ws://localhost:8080/api/agent/ws/{game_id}?token={token}"
    ) as ws:
        while True:
            msg = json.loads(await ws.recv())

            if msg["type"] == "GameOver":
                print(f"Game over! Winner: {msg['winner']}")
                break

            if msg["type"] == "StateUpdate":
                if msg["actingPlayerId"] != "P1":
                    continue  # not our turn

                legal = msg["legalActions"]
                pending = msg.get("pendingDecision")

                if pending:
                    # Handle decision (simple: always choose first option)
                    response = make_decision_response(pending)
                    await ws.send(json.dumps({"type": "SubmitDecision", "decision": response}))
                elif legal:
                    # Choose action (simple: pick last non-pass action, or pass)
                    action = choose_action(legal)
                    await ws.send(json.dumps({"type": "SubmitAction", "actionId": action["actionId"]}))

def choose_action(legal_actions):
    non_pass = [a for a in legal_actions if a["actionType"] != "PassPriority"]
    return non_pass[-1] if non_pass else legal_actions[0]

def make_decision_response(decision):
    dtype = decision["type"]
    if dtype == "YesNoDecision":
        return {"type": "YesNoResponse", "value": True}
    if dtype == "SelectCardsDecision":
        n = decision["minCards"]
        return {"type": "SelectCardsResponse", "cardIds": decision["validCards"][:n]}
    if dtype == "ChooseModeDecision":
        return {"type": "ChooseModeResponse", "selectedModes": [0]}
    if dtype == "ChooseTargetsDecision":
        req = decision["requirements"][0]
        target_id = req["validTargets"][0]
        return {"type": "ChooseTargetsResponse", "targets": [{"type": "Permanent", "id": target_id}]}
    raise ValueError(f"Unknown decision type: {dtype}")

asyncio.run(play_game())
```

---

## Playing in a tournament

Create an AI tournament via the existing `POST /api/dev/ai-tournament` endpoint and specify
`"type": "EXTERNAL"` agents in the agent registry. The external agent connects via WebSocket for each
match, using the same protocol as a standalone game.

See [`docs/plans/ai-full-capabilities.md`](plans/ai-full-capabilities.md) Phase 3 for the agent
registry design.

---

## Security notes

- Tokens are per-game, per-player, single-use UUIDs. They are not reusable across games.
- The API must be behind a firewall or VPN in production — there is no authentication beyond the
  token. Anyone who knows a token can control that player slot.
- `game.external-agent.enabled=false` (the default) disables all endpoints.
- Action payloads are validated server-side; submitting an illegal `actionId` returns an `Error`
  message and does not crash the game.
