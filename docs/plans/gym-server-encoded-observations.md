# Gym-Server Encoded Observations — Implementation Plan

## Problem

The Python MCTS loop calls `step()` for every simulation. With 200 simulations per move and 40 moves
per game, each game requires ~8,000 HTTP round-trips. If each returns a full `TrainingObservation`
JSON (~10–20 KB), the Python client spends the majority of its time deserializing game state and
re-encoding it to sparse indices — work that the server could do once.

| | Python-side encoding (current) | Server-side encoding (this plan) |
|---|---|---|
| Per-step payload | ~10–20 KB JSON | ~0.5–2 KB (int array + action list) |
| 200 sims × 40 moves | ~160 MB / game | ~4 MB / game |
| Encoding work | Python parses + encodes | Server encodes, Python stores |
| FeatureMap owner | Python process | Server (shared via API) |

## Design

### New endpoint: `POST /envs/{id}/step-encoded`

Advances the env by one action and returns pre-encoded sparse feature indices instead of the full
`TrainingObservation`. The `FeatureMap` lives on the server; Python never sees raw card names.

Request body: `{ "actionId": 2 }`

Response:

```json
{
  "terminated": false,
  "winnerId": null,
  "agentToAct": "P1",
  "isPlayer": true,
  "stateIndices": [12, 45, 789, 1203, 4501],
  "actionType": 0,
  "legalActions": [
    { "actionId": 0, "head": "priority", "slot": 0,  "description": "Pass" },
    { "actionId": 1, "head": "priority", "slot": 3,  "description": "Cast Lightning Bolt" },
    { "actionId": 2, "head": "binary",   "slot": 0,  "description": "Declare Attackers" }
  ]
}
```

- `stateIndices`: sorted sparse feature indices ready for `EmbeddingBag`. Computed by a Kotlin
  `MageZeroStateEncoder` using the same `FeatureMap` as every other request.
- `actionType`: int matching MageZero's `ActionType` enum (0=PRIORITY, 3=CHOOSE_TARGET, 5=CHOOSE_USE).
- `isPlayer`: true when `agentToAct == perspectivePlayerId`.
- `legalActions`: each action pre-encoded to `(head, slot)` by the server. Python stores the slot
  directly into the policy vector without hashing.

### Companion: `GET /envs/feature-map`

Returns the full FeatureMap so Python can sync it before training. Needed for the `ignore.roar`
generation in `train.py` (which expects to know the full feature vocabulary).

Response: `{ "version": "argentum-v1", "size": 84201, "entries": [["Pass", 0], ...] }`

Python syncs it once at startup and saves to `data/features/argentum-v1.json`.

### Companion: `POST /envs` with encoded flag

Add `"returnEncoded": true` to `EnvConfig` so the opening observation also uses the encoded format.

### FeatureMap on the server

A singleton `FeatureMapService` bean in gym-server manages a shared `FeatureMap` instance:
- Backed by a JSON file at `data/features/argentum-v1.json` (configurable).
- Loaded at startup, saved on shutdown and periodically.
- All encoding calls share the same instance (thread-safe via `ConcurrentHashMap`).

The Kotlin `MageZeroStateEncoder` is a port of the Python `encoder.py`, reading directly from
`GameState` (not from `TrainingObservation`) for maximum efficiency.

The Kotlin `MageZeroActionEncoder` replicates the Java `String.hashCode()` formula and the
hardcoded slot table from `ActionEncoder.java`, using Argentum's description format.

## Files to create / modify

### gym-server (Kotlin)

| File | Change |
|---|---|
| `gym-server/.../makezero/FeatureMapService.kt` | New — singleton FeatureMap bean |
| `gym-server/.../makezero/MageZeroStateEncoder.kt` | New — GameState → sparse indices |
| `gym-server/.../makezero/MageZeroActionEncoder.kt` | New — LegalAction → (head, slot) |
| `gym-server/.../dto/GymDtos.kt` | Add `EncodedStepResult`, `FeatureMapResponse` |
| `gym-server/.../controller/EnvController.kt` | Add `/step-encoded`, `/feature-map` |
| `gym-server/.../config/GymBeansConfig.kt` | Wire `FeatureMapService` |
| `gym-server/.../application.yml` | Add `gym.feature-map-path` property |

### gym (Kotlin — shared library)

| File | Change |
|---|---|
| `gym/.../makezero/FeatureMap.kt` | New — shared FeatureMap (used by gym-server and gym-trainer) |
| `gym/.../makezero/MageZeroStateFeaturizer.kt` | New — `StateFeaturizer<SparseFeatures>` using FeatureMap |
| `gym/.../makezero/MageZeroActionFeaturizer.kt` | New — `ActionFeaturizer` using hashCode slots |
| `gym/.../makezero/SparseFeatures.kt` | New — `@JvmInline value class SparseFeatures(val indices: IntArray)` |

## Implementation order

1. `gym/.../makezero/FeatureMap.kt` — the foundation everything else uses
2. `gym/.../makezero/SparseFeatures.kt`
3. `gym/.../makezero/MageZeroStateFeaturizer.kt`
4. `gym/.../makezero/MageZeroActionFeaturizer.kt`
5. `gym-server/.../makezero/FeatureMapService.kt`
6. `gym-server/.../controller/EnvController.kt` — add endpoints
7. `gym-server/.../dto/GymDtos.kt` — add DTOs

## When to do this

**After** the Python package validates the end-to-end pipeline (first run producing `model.pt.gz`).
The Python package is Phase 1 and works today with `TrainingObservation`. This is Phase 2 and
replaces the Python encoder with a server-side one for performance. The HDF5 format is identical;
`train.py` does not change.
