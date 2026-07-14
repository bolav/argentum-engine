package com.wingedsheep.engine.handlers.effects.zones

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.WarpExiledComponent
import com.wingedsheep.engine.state.permissions.MayPlayPermission
import com.wingedsheep.engine.state.permissions.addMayPlayPermission
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.effects.WarpExileEffect
import kotlin.reflect.KClass

/**
 * Executor for WarpExileEffect.
 *
 * Exiles a warped permanent, grants its owner permission to cast it from
 * exile on a later turn (CR 702.185a — regular mana cost; warp's alternative
 * cost is hand-only), and marks it with [WarpExiledComponent] as the rule
 * 702.185b "warped card in exile" identifier (used by cards like Close
 * Encounter and Blade of the Swarm).
 *
 * Used by the warp mechanic's delayed trigger that fires at the beginning
 * of the next end step.
 */
class WarpExileExecutor : EffectExecutor<WarpExileEffect> {

    override val effectType: KClass<WarpExileEffect> = WarpExileEffect::class

    override fun execute(
        state: GameState,
        effect: WarpExileEffect,
        context: EffectContext
    ): EffectResult {
        val targetId = context.resolveTarget(effect.target, state)
            ?: return EffectResult.success(state) // Permanent may have already left the battlefield

        val container = state.getEntity(targetId)
            ?: return EffectResult.success(state)

        container.get<CardComponent>()
            ?: return EffectResult.success(state)

        // Only exile if the permanent is still on the battlefield
        if (targetId !in state.getBattlefield()) return EffectResult.success(state)

        // CR 603.7c: the delayed trigger tracks the specific object that was warped in. Entity
        // ids survive zone round-trips in this engine, so a permanent that left the battlefield
        // and returned (blink) is still findable by id — but it's a new object (CR 400.7) the
        // trigger must not exile. Detect that via the battlefield-entry timestamp snapshotted
        // when the trigger was created.
        if (effect.enteredBattlefieldTimestamp != null) {
            val currentEntry = container
                .get<BattlefieldEntryTimestampComponent>()
                ?.timestamp
            if (currentEntry != effect.enteredBattlefieldTimestamp) {
                return EffectResult.success(state)
            }
        }

        // Use ZoneTransitionService for proper cleanup (strip battlefield components, etc.)
        val transitionResult = ZoneTransitionService.moveToZone(
            state = state,
            entityId = targetId,
            destinationZone = Zone.EXILE
        )

        // Grant cast-from-exile permission (regular mana cost — warp's alt cost is
        // hand-only per CR 702.185a) and mark the card as a "warped" exile (CR 702.185b).
        var newState = transitionResult.state.updateEntity(targetId) { c ->
            c.with(WarpExiledComponent(controllerId = context.controllerId))
        }

        val (permId, stateWithPerm) = newState.newEntity()
        newState = stateWithPerm.addMayPlayPermission(
            MayPlayPermission(
                id = permId,
                cardIds = setOf(targetId),
                controllerId = context.controllerId,
                sourceId = context.sourceId,
                permanent = true,
                timestamp = state.timestamp,
            )
        )

        return EffectResult.success(newState, transitionResult.events)
    }
}
