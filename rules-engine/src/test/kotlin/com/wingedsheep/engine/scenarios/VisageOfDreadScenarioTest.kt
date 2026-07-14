package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.lci.cards.VisageOfDread
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Scenario tests for Visage of Dread // Dread Osseosaur (LCI #129).
 *
 * Front — Visage of Dread ({1}{B} Artifact):
 *   When this artifact enters, target opponent reveals their hand. You choose an
 *   artifact or creature card from it. That player discards that card.
 *   Craft with two creatures {5}{B} (CR 702.167 — exactly two materials, from among
 *   creatures you control and/or creature cards in your graveyard).
 *
 * Back — Dread Osseosaur (Creature — Dinosaur Skeleton Horror 5/4):
 *   Menace
 *   Whenever this creature enters or attacks, you may mill two cards.
 *
 * Coverage:
 * 1. Front ETB — targeted reveal-choose-discard flow; only artifact/creature cards
 *    are offered; the chosen card is discarded to its owner's graveyard.
 * 2. Craft with two battlefield creatures — materials exiled, source returns
 *    transformed as a 5/4 menace Dread Osseosaur, and the back face's *enters*
 *    trigger fires (the craft return is an entry) → accept the may → mill two.
 * 3. Craft mixing one battlefield creature + one graveyard creature card
 *    (CR 702.167b) — decline the may-mill.
 * 4. Negative — exactly two materials required: one or three are rejected.
 * 5. Back-face attack trigger — attacking mills two on accept.
 */
class VisageOfDreadScenarioTest : FunSpec({

    val projector = StateProjector()

    fun setup(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), skipMulligans = true)
        return driver
    }

    // The craft is the front face's only activated ability.
    fun craftAbilityId() = VisageOfDread.activatedAbilities.single().id

    // Pass priority until a YesNoDecision (the may-mill gate) is pending.
    fun GameTestDriver.passUntilYesNo() {
        var guard = 0
        while (state.pendingDecision !is YesNoDecision && guard++ < 10) bothPass()
        (state.pendingDecision as? YesNoDecision).shouldNotBeNull()
    }

    // Craft Visage of Dread with the given materials and resolve the ability.
    fun GameTestDriver.craftVisage(player: EntityId, visage: EntityId, materials: List<EntityId>) {
        giveMana(player, Color.BLACK, 6)
        submitSuccess(
            ActivateAbility(
                playerId = player,
                sourceId = visage,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = materials)
            )
        )
        bothPass() // resolve the craft ability: exile materials, return transformed
    }

    test("ETB: target opponent reveals their hand, you choose their creature card, they discard it") {
        val driver = setup()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // TWO choosable cards — with a single legal choice the engine auto-selects
        // without surfacing the SelectCardsDecision.
        val bears = driver.putCardInHand(opponent, "Grizzly Bears")
        val lattice = driver.putCardInHand(opponent, "Saheeli's Lattice")
        val land = driver.putCardInHand(opponent, "Swamp")
        val gyBefore = driver.getGraveyard(opponent).size

        val visage = driver.putCardInHand(you, "Visage of Dread")
        driver.giveMana(you, Color.BLACK, 1)
        driver.giveColorlessMana(you, 1)
        driver.castSpell(you, visage).error shouldBe null
        driver.bothPass() // artifact spell resolves; the ETB trigger wants its target

        // Drive the trigger: choose the target opponent, then reach the card choice.
        var guard = 0
        while (driver.state.pendingDecision !is SelectCardsDecision && guard++ < 30) {
            when (driver.state.pendingDecision) {
                is ChooseTargetsDecision -> driver.submitTargetSelection(you, listOf(opponent))
                else -> driver.bothPass()
            }
        }

        val decision = driver.state.pendingDecision as? SelectCardsDecision
            ?: error("Expected a SelectCardsDecision to choose the card to discard")
        // Only artifact or creature cards may be chosen — the land is excluded.
        decision.options.contains(bears) shouldBe true
        decision.options.contains(lattice) shouldBe true
        decision.options.contains(land) shouldBe false

        driver.submitCardSelection(you, listOf(bears))

        // The chosen creature card was discarded to the opponent's graveyard.
        driver.getGraveyard(opponent).contains(bears) shouldBe true
        (driver.getGraveyard(opponent).size - gyBefore) shouldBe 1
        driver.getHand(opponent).contains(land) shouldBe true
        driver.getHand(opponent).contains(lattice) shouldBe true

        // The artifact itself stayed on the battlefield as its front face.
        driver.findPermanent(you, "Visage of Dread").shouldNotBeNull()
    }

    test("craft with two battlefield creatures: both exiled, returns as 5/4 menace Dread Osseosaur, enters trigger mills two on accept") {
        val driver = setup()
        val you = driver.activePlayer!!

        val visage = driver.putPermanentOnBattlefield(you, "Visage of Dread")
        val bear1 = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val bear2 = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val libraryBefore = driver.state.getZone(you, Zone.LIBRARY).size
        val gyBefore = driver.getGraveyard(you).size

        driver.craftVisage(you, visage, listOf(bear1, bear2))

        // Both materials exiled (CR 702.167a).
        driver.getExile(you).shouldContainAll(listOf(bear1, bear2))

        // Source returned to the battlefield as its back face.
        val container = driver.state.getEntity(visage)
        container.shouldNotBeNull()
        val card = container.get<CardComponent>()
        card.shouldNotBeNull()
        card.name shouldBe "Dread Osseosaur"
        card.typeLine.cardTypes shouldBe setOf(CardType.CREATURE)
        card.typeLine.subtypes shouldBe setOf(Subtype.DINOSAUR, Subtype.SKELETON, Subtype.HORROR)
        container.get<DoubleFacedComponent>().shouldNotBeNull().currentFace shouldBe DoubleFacedComponent.Face.BACK

        // 5/4 with menace.
        val projected = projector.project(driver.state)
        projected.getPower(visage) shouldBe 5
        projected.getToughness(visage) shouldBe 4
        projected.hasKeyword(visage, Keyword.MENACE) shouldBe true

        // The craft return is an entry — the back face's enters trigger fires.
        driver.passUntilYesNo()
        driver.submitYesNo(you, true)

        driver.state.getZone(you, Zone.LIBRARY).size shouldBe libraryBefore - 2
        driver.getGraveyard(you).size shouldBe gyBefore + 2
    }

    test("craft materials may mix a battlefield creature and a graveyard creature card (CR 702.167b); declining the may-mill mills nothing") {
        val driver = setup()
        val you = driver.activePlayer!!

        val visage = driver.putPermanentOnBattlefield(you, "Visage of Dread")
        val battlefieldBear = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val graveyardBear = driver.putCardInGraveyard(you, "Grizzly Bears")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val libraryBefore = driver.state.getZone(you, Zone.LIBRARY).size

        driver.craftVisage(you, visage, listOf(battlefieldBear, graveyardBear))

        driver.getExile(you).shouldContainAll(listOf(battlefieldBear, graveyardBear))
        driver.state.getEntity(visage)!!.get<CardComponent>()!!.name shouldBe "Dread Osseosaur"

        // Decline the enters may-mill: nothing is milled.
        driver.passUntilYesNo()
        driver.submitYesNo(you, false)

        driver.state.getZone(you, Zone.LIBRARY).size shouldBe libraryBefore
        // The graveyard creature card left the graveyard for exile and nothing replaced it.
        driver.getGraveyard(you).contains(graveyardBear) shouldBe false
    }

    test("craft requires exactly two materials: one or three creatures are rejected") {
        val driver = setup()
        val you = driver.activePlayer!!

        val visage = driver.putPermanentOnBattlefield(you, "Visage of Dread")
        val bear1 = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val bear2 = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val bear3 = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(you, Color.BLACK, 6)

        // One material — below the exact count.
        driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = visage,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(bear1))
            )
        ).isSuccess shouldBe false

        // Three materials — above the exact count.
        driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = visage,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(bear1, bear2, bear3))
            )
        ).isSuccess shouldBe false

        // Nothing happened: still the front face, no materials exiled.
        driver.state.getEntity(visage)!!.get<CardComponent>()!!.name shouldBe "Visage of Dread"
        driver.getExile(you).isEmpty() shouldBe true
    }

    test("attack trigger: whenever Dread Osseosaur attacks, you may mill two cards") {
        val driver = setup()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)

        val visage = driver.putPermanentOnBattlefield(you, "Visage of Dread")
        val bear1 = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val bear2 = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        driver.craftVisage(you, visage, listOf(bear1, bear2))

        // Decline the enters-trigger mill so the attack trigger's mill is isolated.
        driver.passUntilYesNo()
        driver.submitYesNo(you, false)

        // The crafted creature entered this turn — clear sickness so it can attack.
        driver.removeSummoningSickness(visage)

        val libraryBefore = driver.state.getZone(you, Zone.LIBRARY).size
        val gyBefore = driver.getGraveyard(you).size

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(you, listOf(visage), opponent).error shouldBe null

        // The attack trigger resolves into the may-mill gate.
        driver.passUntilYesNo()
        driver.submitYesNo(you, true)

        driver.state.getZone(you, Zone.LIBRARY).size shouldBe libraryBefore - 2
        driver.getGraveyard(you).size shouldBe gyBefore + 2
    }
})
