package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.CraftedFromExiledComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.lci.cards.JadeSeedstones
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Scenario tests for Jade Seedstones // Jadeheart Attendant (LCI #195).
 *
 * Front — Jade Seedstones ({3}{G}, Artifact):
 *   "When this artifact enters, distribute three +1/+1 counters among one, two, or
 *    three target creatures you control."
 *   "Craft with creature {5}{G}{G}" — exactly ONE creature material (CR 702.167a),
 *    from among creatures you control and/or creature cards in your graveyard.
 *
 * Back — Jadeheart Attendant (Artifact Creature — Golem, 7/7):
 *   "When this creature enters, you gain life equal to the mana value of the exiled
 *    card used to craft it."
 *
 * Covers:
 *  - Front ETB distribute trigger end-to-end (cast from hand, ChooseTargetsDecision,
 *    3 counters split across two targets).
 *  - Craft with a battlefield creature: material exiled, source returns transformed as
 *    a 7/7 Jadeheart Attendant, and its ETB gains life equal to the material's mana value.
 *  - Craft with a graveyard creature card: same life gain from the card's mana value.
 *  - Negative: a noncreature artifact is not a legal material.
 *  - Negative: two creatures are rejected ("Craft with creature" = exactly one).
 */
class JadeSeedstonesScenarioTest : FunSpec({

    // Test materials with distinct, known mana values so the life gain tells us
    // exactly which card's mana value was read.
    val bigYak = CardDefinition.creature(
        name = "Test Big Yak",
        manaCost = ManaCost.parse("{4}{R}"), // mana value 5
        subtypes = setOf(Subtype("Yak")),
        power = 3,
        toughness = 3,
        oracleText = ""
    )
    val smallYak = CardDefinition.creature(
        name = "Test Small Yak",
        manaCost = ManaCost.parse("{2}{G}"), // mana value 3
        subtypes = setOf(Subtype("Yak")),
        power = 2,
        toughness = 2,
        oracleText = ""
    )
    val trinket = CardDefinition.artifact(
        name = "Test Trinket",
        manaCost = ManaCost.parse("{2}"),
        oracleText = ""
    )

    val projector = StateProjector()

    fun setup(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(bigYak, smallYak, trinket))
        driver.initMirrorMatch(
            deck = Deck.of("Forest" to 40),
            skipMulligans = true
        )
        return driver
    }

    // The front face's only activated ability is the Craft (the distribute ETB is a
    // triggered ability and lives in a separate list).
    fun craftAbilityId() = JadeSeedstones.activatedAbilities.single().id

    fun plusOneCounters(driver: GameTestDriver, id: com.wingedsheep.sdk.model.EntityId): Int =
        driver.state.getEntity(id)?.get<CountersComponent>()?.getCount(CounterType.PLUS_ONE_PLUS_ONE) ?: 0

    // ─── Front face: ETB distribute trigger ─────────────────────────────────

    test("ETB distributes three +1/+1 counters among two target creatures you control") {
        val d = setup()
        val p1 = d.activePlayer!!

        val first = d.putCreatureOnBattlefield(p1, "Test Big Yak")
        val second = d.putCreatureOnBattlefield(p1, "Test Small Yak")
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        d.giveMana(p1, Color.GREEN, 4)

        val card = d.putCardInHand(p1, "Jade Seedstones")
        val cast = d.castSpell(p1, card)
        withClue("casting Jade Seedstones: ${cast.error}") { cast.error shouldBe null }

        // Resolve the artifact spell — it enters, and the ETB trigger asks for its
        // one-to-three targets before going on the stack.
        d.bothPass()
        withClue("expected the ETB trigger's target selection") {
            d.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        }
        d.submitTargetSelection(p1, listOf(first, second)).error shouldBe null

        // Resolve the trigger. The engine splits the three counters evenly across the
        // chosen targets, remainder to the first (2 on `first`, 1 on `second`); if a
        // distribution choice is ever offered instead, answer with the same split.
        d.bothPass()
        (d.pendingDecision as? DistributeDecision)?.let { decision ->
            d.submitDecision(p1, DistributionResponse(decision.id, mapOf(first to 2, second to 1)))
            d.bothPass()
        }

        withClue("three counters distributed 2/1 across the two targets") {
            plusOneCounters(d, first) shouldBe 2
            plusOneCounters(d, second) shouldBe 1
        }
    }

    // ─── Craft: battlefield creature material + back-face ETB life gain ─────

    test("craft with a battlefield creature exiles it and Jadeheart Attendant's ETB gains life equal to its mana value") {
        val d = setup()
        val p1 = d.activePlayer!!

        val seedstones = d.putPermanentOnBattlefield(p1, "Jade Seedstones")
        val material = d.putCreatureOnBattlefield(p1, "Test Big Yak") // mana value 5
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        d.giveMana(p1, Color.GREEN, 7)
        val lifeBefore = d.getLifeTotal(p1)

        d.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = seedstones,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(material))
            )
        )
        // Resolve the craft ability itself...
        d.bothPass()

        // Material exiled; source returned to the battlefield as the back face.
        d.getExile(p1) shouldContain material

        val container = d.state.getEntity(seedstones)
        container.shouldNotBeNull()
        val cardComponent = container.get<CardComponent>()
        cardComponent.shouldNotBeNull()
        cardComponent.name shouldBe "Jadeheart Attendant"
        cardComponent.typeLine.cardTypes shouldBe setOf(CardType.ARTIFACT, CardType.CREATURE)
        cardComponent.typeLine.subtypes shouldBe setOf(Subtype.GOLEM)

        val dfc = container.get<DoubleFacedComponent>()
        dfc.shouldNotBeNull()
        dfc.currentFace shouldBe DoubleFacedComponent.Face.BACK

        val crafted = container.get<CraftedFromExiledComponent>()
        crafted.shouldNotBeNull()
        crafted.exiledIds shouldBe listOf(material)

        // A 7/7 Golem.
        val projected = projector.project(d.state)
        projected.getPower(seedstones) shouldBe 7
        projected.getToughness(seedstones) shouldBe 7

        // ...then resolve the back face's ETB trigger: gain life equal to the mana
        // value of the exiled card used to craft it ({4}{R} = 5).
        d.bothPass()
        withClue("gained life equal to the material's mana value (5)") {
            d.getLifeTotal(p1) shouldBe lifeBefore + 5
        }
    }

    // ─── Craft: graveyard creature card material ─────────────────────────────

    test("craft with a creature card from the graveyard also gains its mana value") {
        val d = setup()
        val p1 = d.activePlayer!!

        val seedstones = d.putPermanentOnBattlefield(p1, "Jade Seedstones")
        val material = d.putCardInGraveyard(p1, "Test Small Yak") // mana value 3
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        d.giveMana(p1, Color.GREEN, 7)
        val lifeBefore = d.getLifeTotal(p1)

        d.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = seedstones,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(material))
            )
        )
        // Resolve the craft ability, then the back-face ETB trigger.
        d.bothPass()
        d.bothPass()

        d.getExile(p1) shouldContain material
        d.state.getEntity(seedstones)!!.get<CardComponent>()!!.name shouldBe "Jadeheart Attendant"
        withClue("gained life equal to the graveyard material's mana value (3)") {
            d.getLifeTotal(p1) shouldBe lifeBefore + 3
        }
    }

    // ─── Negatives: material validation (exactly one creature) ──────────────

    test("rejects a noncreature artifact as craft material") {
        val d = setup()
        val p1 = d.activePlayer!!

        val seedstones = d.putPermanentOnBattlefield(p1, "Jade Seedstones")
        val artifact = d.putPermanentOnBattlefield(p1, "Test Trinket")
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        d.giveMana(p1, Color.GREEN, 7)

        val result = d.submit(
            ActivateAbility(
                playerId = p1,
                sourceId = seedstones,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(artifact))
            )
        )
        result.isSuccess shouldBe false
        // The front face is still on the battlefield, nothing exiled.
        d.state.getEntity(seedstones)!!.get<CardComponent>()!!.name shouldBe "Jade Seedstones"
        d.getExile(p1) shouldBe emptyList()
    }

    test("rejects two creatures as craft materials (Craft with creature = exactly one)") {
        val d = setup()
        val p1 = d.activePlayer!!

        val seedstones = d.putPermanentOnBattlefield(p1, "Jade Seedstones")
        val first = d.putCreatureOnBattlefield(p1, "Test Big Yak")
        val second = d.putCreatureOnBattlefield(p1, "Test Small Yak")
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        d.giveMana(p1, Color.GREEN, 7)

        val result = d.submit(
            ActivateAbility(
                playerId = p1,
                sourceId = seedstones,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(first, second))
            )
        )
        result.isSuccess shouldBe false
        d.state.getEntity(seedstones)!!.get<CardComponent>()!!.name shouldBe "Jade Seedstones"
        d.getExile(p1) shouldBe emptyList()
    }
})
