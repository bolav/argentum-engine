package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.WarpedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Regression scenario for the warp + blink interaction (CR 603.7c / 400.7).
 *
 * Starfield Shepherd (EOE) is cast for its warp cost {1}{W}, then Daydream (SOS) exiles it
 * and returns it to the battlefield. The returned permanent is a new object, so warp's
 * "exile this creature at the beginning of the next end step" delayed trigger must not
 * exile it — the Shepherd stays on the battlefield permanently.
 */
class StarfieldShepherdDaydreamScenarioTest : ScenarioTestBase() {

    init {
        context("warp exile tracks the object, not the entity") {

            test("Daydreamed Starfield Shepherd is not exiled by the warp trigger at end of turn") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardInHand(1, "Starfield Shepherd")
                    .withCardInHand(1, "Daydream")
                    .withLandsOnBattlefield(1, "Plains", 3)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                fun shepherdOnBattlefield() =
                    game.state.getBattlefield(game.player1Id).find { entityId ->
                        game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Starfield Shepherd"
                    }

                // Cast the Shepherd for its warp cost {1}{W}.
                val cast = game.castSpellWithAlternativeCost(1, "Starfield Shepherd")
                withClue("Warp cast should succeed: ${cast.error}") { cast.error shouldBe null }
                game.resolveStack()

                // The ETB search trigger surfaces a library selection — fail to find.
                val search = game.state.pendingDecision as? SelectCardsDecision
                    ?: error("expected the ETB library-search decision; got ${game.state.pendingDecision}")
                game.submitDecision(CardsSelectedResponse(search.id, emptyList()))
                game.resolveStack()

                val warped = shepherdOnBattlefield()
                    ?: error("Starfield Shepherd should be on the battlefield after the warp cast")
                game.state.getEntity(warped)?.has<WarpedComponent>() shouldBe true

                // Daydream it: exile and return — the Shepherd comes back as a new object.
                val daydream = game.castSpell(1, "Daydream", targetId = warped)
                withClue("Daydream cast should succeed: ${daydream.error}") { daydream.error shouldBe null }
                game.resolveStack()

                val returned = shepherdOnBattlefield()
                    ?: error("Starfield Shepherd should have returned to the battlefield")
                game.state.getEntity(returned)?.has<WarpedComponent>() shouldBe false

                // Advance to the end step and drain the warp delayed trigger — it must not
                // exile the returned (new) object.
                game.passUntilPhase(Phase.ENDING, Step.END)
                game.resolveStack()

                withClue("blinked Shepherd must survive the warp end-step trigger (CR 603.7c)") {
                    shepherdOnBattlefield() shouldNotBe null
                }
                withClue("nothing should have been warp-exiled") {
                    game.state.getZone(ZoneKey(game.player1Id, Zone.EXILE)) shouldBe emptyList()
                }
            }
        }
    }
}
