package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.CraftedFromExiledComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.lci.cards.SunbirdStandard
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Scenario tests for Sunbird Standard // Sunbird Effigy (LCI #262).
 *
 * Covers:
 *  - Front face: "{T}: Add one mana of any color." — ChooseColorDecision, chosen color
 *    lands in the pool.
 *  - Craft with one or more {5} (CR 702.167): any permanents you control and/or cards in
 *    your graveyard as materials; source returns transformed as Sunbird Effigy.
 *  - Back-face P/T CDA: power and toughness each equal the number of distinct colors among
 *    the exiled materials (colorless materials contribute nothing).
 *  - Back-face keywords: flying, vigilance, haste (haste exercised implicitly — the mana
 *    ability is activated the turn the Effigy enters).
 *  - Back-face mana ability: "{T}: For each color among the exiled cards used to craft
 *    this creature, add one mana of that color." — exactly one of each crafted color, no
 *    color choice.
 *  - All-colorless craft: the Effigy is 0/0 and immediately dies to state-based actions,
 *    landing in the graveyard as its front face (CR 704.5f + 712.8a).
 *  - Validation: craft rejected when the activator supplies no materials.
 */
class SunbirdStandardScenarioTest : FunSpec({

    // Test materials with distinct printed colors (colors derive from mana cost).
    val whiteCreature = CardDefinition.creature(
        name = "Test White Bird",
        manaCost = ManaCost.parse("{W}"),
        subtypes = setOf(Subtype("Bird")),
        power = 1,
        toughness = 1,
        oracleText = ""
    )
    val redCreature = CardDefinition.creature(
        name = "Test Red Raider",
        manaCost = ManaCost.parse("{R}"),
        subtypes = setOf(Subtype("Human")),
        power = 2,
        toughness = 1,
        oracleText = ""
    )
    val colorlessTrinket = card("Test Trinket") {
        manaCost = "{2}"
        colorIdentity = ""
        typeLine = "Artifact"
        oracleText = ""
    }

    val projector = StateProjector()

    fun setup(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(whiteCreature, redCreature, colorlessTrinket))
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            skipMulligans = true
        )
        return driver
    }

    // Front face: [0] = "{T}: Add one mana of any color." (mana ability), [1] = Craft.
    val frontManaAbilityId = SunbirdStandard.activatedAbilities.first { it.isManaAbility }.id
    val craftAbilityId = SunbirdStandard.activatedAbilities.first { !it.isManaAbility }.id
    // Back face: the Effigy's only activated ability is its mana ability.
    val effigyManaAbilityId = SunbirdStandard.backFace!!.activatedAbilities.single().id

    fun pool(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId): ManaPoolComponent =
        driver.state.getEntity(player)?.get<ManaPoolComponent>() ?: ManaPoolComponent()

    test("front face taps for one mana of the chosen color") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val standard = driver.putPermanentOnBattlefield(p1, "Sunbird Standard")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val result = driver.submit(
            ActivateAbility(
                playerId = p1,
                sourceId = standard,
                abilityId = frontManaAbilityId
            )
        )
        // A mana ability resolving into a color choice pauses on a decision.
        result.error shouldBe null

        val decision = driver.pendingDecision
        decision.shouldNotBeNull()
        decision.shouldBeInstanceOf<ChooseColorDecision>()
        driver.submitDecision(p1, ColorChosenResponse(decision.id, Color.GREEN))

        driver.isTapped(standard) shouldBe true
        val manaPool = pool(driver, p1)
        manaPool.green shouldBe 1
        manaPool.white shouldBe 0
        manaPool.colorless shouldBe 0
    }

    test("craft exiles materials from battlefield and graveyard, returns as Sunbird Effigy with P/T = colors among materials") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val standard = driver.putPermanentOnBattlefield(p1, "Sunbird Standard")
        val white = driver.putCreatureOnBattlefield(p1, "Test White Bird")
        val red = driver.putCreatureOnBattlefield(p1, "Test Red Raider")
        val trinket = driver.putCardInGraveyard(p1, "Test Trinket")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.WHITE, 5)

        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = standard,
                abilityId = craftAbilityId,
                costPayment = AdditionalCostPayment(exiledCards = listOf(white, red, trinket))
            )
        )
        driver.bothPass()

        // Materials exiled.
        driver.state.getZone(ZoneKey(p1, Zone.EXILE))
            .shouldContainExactlyInAnyOrder(white, red, trinket)

        // Source returned to the battlefield transformed.
        val container = driver.state.getEntity(standard)
        container.shouldNotBeNull()
        val cardComponent = container.get<CardComponent>()
        cardComponent.shouldNotBeNull()
        cardComponent.name shouldBe "Sunbird Effigy"
        cardComponent.typeLine.cardTypes shouldBe setOf(CardType.ARTIFACT, CardType.CREATURE)
        container.get<DoubleFacedComponent>()!!.currentFace shouldBe DoubleFacedComponent.Face.BACK
        container.get<CraftedFromExiledComponent>()!!.exiledIds.toSet() shouldBe
            setOf(white, red, trinket)

        // P/T CDA: two distinct colors (W, R) among materials — the colorless trinket adds
        // nothing — so the Effigy is 2/2, with flying, vigilance and haste.
        val projected = projector.project(driver.state)
        projected.getPower(standard) shouldBe 2
        projected.getToughness(standard) shouldBe 2
        projected.hasKeyword(standard, Keyword.FLYING) shouldBe true
        projected.hasKeyword(standard, Keyword.VIGILANCE) shouldBe true
        projected.hasKeyword(standard, Keyword.HASTE) shouldBe true
    }

    test("Sunbird Effigy's mana ability adds exactly one mana of each crafted color") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val standard = driver.putPermanentOnBattlefield(p1, "Sunbird Standard")
        val white = driver.putCreatureOnBattlefield(p1, "Test White Bird")
        val red = driver.putCreatureOnBattlefield(p1, "Test Red Raider")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.WHITE, 5)

        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = standard,
                abilityId = craftAbilityId,
                costPayment = AdditionalCostPayment(exiledCards = listOf(white, red))
            )
        )
        driver.bothPass()
        driver.state.getEntity(standard)!!.get<CardComponent>()!!.name shouldBe "Sunbird Effigy"

        // The Effigy entered this turn: tapping for mana right away also exercises haste.
        // No decision — the ability adds one mana of each crafted color, no choice.
        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = standard,
                abilityId = effigyManaAbilityId
            )
        )

        driver.isTapped(standard) shouldBe true
        val manaPool = pool(driver, p1)
        manaPool.white shouldBe 1
        manaPool.red shouldBe 1
        manaPool.blue shouldBe 0
        manaPool.black shouldBe 0
        manaPool.green shouldBe 0
        manaPool.colorless shouldBe 0
    }

    test("crafting with only a colorless material makes a 0/0 that dies to SBAs as its front face") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val standard = driver.putPermanentOnBattlefield(p1, "Sunbird Standard")
        val trinket = driver.putPermanentOnBattlefield(p1, "Test Trinket")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.WHITE, 5)

        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = standard,
                abilityId = craftAbilityId,
                costPayment = AdditionalCostPayment(exiledCards = listOf(trinket))
            )
        )
        driver.bothPass()

        // Material exiled — the craft itself worked.
        driver.state.getZone(ZoneKey(p1, Zone.EXILE)) shouldContain trinket

        // Zero colors among materials → Sunbird Effigy is 0/0 and dies to state-based
        // actions immediately (CR 704.5f). In the graveyard the DFC has only its
        // front-face characteristics (CR 712.8a).
        driver.getGraveyard(p1) shouldContain standard
        val container = driver.state.getEntity(standard)
        container.shouldNotBeNull()
        container.get<CardComponent>()!!.name shouldBe "Sunbird Standard"
        container.get<DoubleFacedComponent>()!!.currentFace shouldBe DoubleFacedComponent.Face.FRONT
    }

    test("rejects craft activation when the activator supplies no materials") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val standard = driver.putPermanentOnBattlefield(p1, "Sunbird Standard")
        driver.putCreatureOnBattlefield(p1, "Test White Bird")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.WHITE, 5)

        val result = driver.submit(
            ActivateAbility(
                playerId = p1,
                sourceId = standard,
                abilityId = craftAbilityId
                // costPayment intentionally omitted — materials are the activator's choice.
            )
        )
        result.isSuccess shouldBe false
    }
})
