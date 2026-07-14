package com.wingedsheep.engine.state.components.stack

import com.wingedsheep.engine.handlers.effects.permanent.counters.counterTypeToString
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageSourceLki
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/**
 * Read-only view of a permanent's characteristics. Implemented by both the *live* projected
 * board ([LiveEntityView], a thin [ProjectedState] adapter) and a *frozen* [EntitySnapshot].
 *
 * This is the single abstraction over "read this permanent's property" that lets last-known
 * information (CR 113.7a / 603.10 / 608.2h) resolve uniformly: a read site asks for an
 * [EntityView] for an entity — live if it is still on the battlefield, otherwise its captured
 * snapshot — and reads the same accessors regardless of whether the permanent is still in play.
 */
interface EntityView {
    val entityId: EntityId
    val power: Int?
    val toughness: Int?
    val controllerId: EntityId?

    /** Counter-type-string → count (e.g. "+1/+1", "-1/-1", "loyalty"). Matches the counter wire format. */
    val counters: Map<String, Int>
    val keywords: Set<String>
    val subtypes: Set<String>
    val supertypes: Set<String>
    val lostAllAbilities: Boolean

    val plusOnePlusOneCounters: Int get() = counters["+1/+1"] ?: 0
    val minusOneMinusOneCounters: Int get() = counters["-1/-1"] ?: 0
    val totalCounters: Int get() = counters.values.sum()
}

/**
 * Live, projection-backed [EntityView]. Reads flow straight through to [GameState.projectedState]
 * (and the entity's [CountersComponent] for counters, which projection does not surface), so the
 * values are always current. Used as the "still on the battlefield" arm of last-known resolution.
 */
class LiveEntityView(
    private val state: GameState,
    override val entityId: EntityId,
) : EntityView {
    private val projected: ProjectedState get() = state.projectedState
    override val power: Int? get() = projected.getPower(entityId)
    override val toughness: Int? get() = projected.getToughness(entityId)
    override val controllerId: EntityId? get() = projected.getController(entityId)
    override val counters: Map<String, Int> get() = countersOf(state, entityId)
    override val keywords: Set<String> get() = projected.getKeywords(entityId)
    override val subtypes: Set<String> get() = projected.getSubtypes(entityId)
    override val supertypes: Set<String> get() = projected.getSupertypes(entityId)
    override val lostAllAbilities: Boolean get() = projected.hasLostAllAbilities(entityId)
}

/**
 * Frozen projected characteristics of a permanent captured at a specific moment — typically just
 * before it leaves the battlefield (CR 112.7a / 603.10 / 608.2h, "as it last existed on the
 * battlefield"). The single last-known-information value type for the whole engine. It backs:
 *
 * - **cost-time references** — sacrificed / tapped / chosen permanents and a self-sacrificing
 *   source (CR 112.7a), so "deals damage equal to its power" reads the pre-cost power; and
 * - **death / leaves-the-battlefield triggers** — carried as a single value on
 *   [com.wingedsheep.engine.core.ZoneChangeEvent.lastKnown] and threaded into trigger resolution,
 *   replacing what used to be ~16 parallel `lastKnown*` scalar fields.
 *
 * The first six parameters preserve the order of the former `PermanentSnapshot` so positional
 * construction at existing call sites is unaffected; the remaining fields (defaulted) carry the
 * death/leave last-known information that previously lived as loose scalars on the event.
 */
@Serializable
data class EntitySnapshot(
    override val entityId: EntityId,
    override val power: Int? = null,
    override val toughness: Int? = null,
    override val subtypes: Set<String> = emptySet(),
    /** Projected supertypes at capture time (e.g. "LEGENDARY", "BASIC", "SNOW", "WORLD"). */
    override val supertypes: Set<String> = emptySet(),
    /**
     * Controller frozen at capture time, NOT at the eventual zone-leave. If control shifts after the
     * snapshot is taken (e.g. Threaten resolves while the ability is on the stack) and the permanent
     * then leaves, this reports the older controller — acceptable for current callers; revisit if a
     * card needs control-at-zone-leave fidelity.
     */
    override val controllerId: EntityId? = null,
    override val counters: Map<String, Int> = emptyMap(),
    override val keywords: Set<String> = emptySet(),
    override val lostAllAbilities: Boolean = false,
    // --- battlefield-exit-only fields (no meaning for a live permanent) ---
    /** Projected type line at capture, so leaves-battlefield triggers see continuous-effect-granted types. */
    val typeLine: TypeLine? = null,
    /** Card definition id, so dies/leaves triggers resolve for tokens after 704.5s cleanup. */
    val cardDefinitionId: String? = null,
    /** The original card name when this permanent entered as a copy (Clever Impersonator). */
    val copyOfOriginalName: String? = null,
    /** For auras/equipment: the entity this was attached to when it left (enchanted-creature dies triggers). */
    val attachedTo: EntityId? = null,
    /** Creatures blocking, or blocked by, this one when it left (CR 509; Abu Ja'far). */
    val blockingOrBlockedByIds: List<EntityId> = emptyList(),
    /** True if the leaving entity was a token (CR 704.5d — suppress persist-style return triggers). */
    val wasToken: Boolean = false,
    /** Per-player damage dealt to this entity this turn, keyed by source-controller (Grothama). */
    val damageDealtByPlayers: Map<EntityId, Int> = emptyMap(),
    /** Snapshots of the sources that dealt damage to this entity this turn (Shelob, Child of Ungoliant). */
    val damageSources: Set<DamageSourceLki> = emptySet(),
    /** The cast-time {X} carried by `CastChoicesComponent`, so dies/leaves triggers read `DynamicAmount.CastX`. */
    val castX: Int? = null,
) : EntityView {
    companion object {
        /**
         * Capture the projection-derivable characteristics of [entityId] from [state], including
         * counters/keywords/lost-abilities. Caller must invoke this BEFORE any zone change so
         * projected values still resolve. The zone-transition path augments the result with the
         * battlefield-exit-only fields ([typeLine], [attachedTo], …) via `copy(...)`.
         */
        fun fromProjection(entityId: EntityId, state: GameState): EntitySnapshot {
            val projected = state.projectedState
            return EntitySnapshot(
                entityId = entityId,
                power = projected.getPower(entityId),
                toughness = projected.getToughness(entityId),
                subtypes = projected.getSubtypes(entityId),
                supertypes = projected.getSupertypes(entityId),
                controllerId = projected.getController(entityId),
                counters = countersOf(state, entityId),
                keywords = projected.getKeywords(entityId),
                lostAllAbilities = projected.hasLostAllAbilities(entityId),
            )
        }
    }
}

/** Counter-type-string → count for [entityId], in the counter wire format. */
private fun countersOf(state: GameState, entityId: EntityId): Map<String, Int> =
    state.getEntity(entityId)?.get<CountersComponent>()
        ?.counters?.filterValues { it > 0 }
        ?.mapKeys { (type, _) -> counterTypeToString(type) }
        ?: emptyMap()

/**
 * Capture frozen [EntitySnapshot]s (projected P/T, subtypes, supertypes, controller) for a list of
 * permanents, in order. Caller must invoke this BEFORE any zone change so projected values still
 * resolve. Used for cost-time last-known information (sacrificed / tapped / chosen permanents).
 */
fun captureEntitySnapshots(
    ids: List<EntityId>,
    projected: ProjectedState,
): List<EntitySnapshot> = ids.map { id ->
    EntitySnapshot(
        entityId = id,
        power = projected.getPower(id),
        toughness = projected.getToughness(id),
        subtypes = projected.getSubtypes(id),
        supertypes = projected.getSupertypes(id),
        controllerId = projected.getController(id),
    )
}

/**
 * [captureEntitySnapshots] overload that also records each permanent's **token-ness** ([EntitySnapshot.wasToken])
 * as last-known information — a fact only [GameState] carries (via [TokenComponent]), not [ProjectedState]. Use at
 * a sacrifice site when a following sibling needs to know whether the sacrificed permanent was a token (Exploit's
 * `ExploitedEvent.sacrificedWasToken`, read by Skull Skaab's "exploits a nontoken creature" clause). Caller must
 * invoke this BEFORE the zone change so both projected values and the token component still resolve.
 */
fun captureEntitySnapshots(
    ids: List<EntityId>,
    state: GameState,
): List<EntitySnapshot> = captureEntitySnapshots(ids, state.projectedState).map { snapshot ->
    snapshot.copy(wasToken = state.getEntity(snapshot.entityId)?.has<TokenComponent>() ?: false)
}

fun List<EntitySnapshot>.snapshotFor(id: EntityId): EntitySnapshot? =
    firstOrNull { it.entityId == id }

val List<EntitySnapshot>.entityIds: List<EntityId>
    get() = map { it.entityId }
