package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.lci.cards.BraidedNet
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.effects.AddManaEffect
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Scenario tests for Braided Net // Braided Quipu (LCI #47).
 *
 * Front — Braided Net ({2}{U}, Artifact):
 *   "This artifact enters with three net counters on it." — [EntersWithCounters]
 *   replacement effect, exercised via a real cast from hand.
 *   "Craft with artifact {1}{U}" — exactly one artifact material (CR 702.167).
 *
 * Front — tap-and-lock ability:
 *   "{T}, Remove a net counter from this artifact: Tap another target nonland permanent.
 *    Its activated abilities can't be activated for as long as it remains tapped."
 *   — taps the target and grants it a self-scoped [PreventActivatedAbilities] for
 *   [Duration.WhileAffectedTapped]. The lock covers mana abilities too (no carve-out on
 *   the printed line), ends the moment the permanent untaps, and — one-way per CR 611.2b —
 *   does NOT come back if the permanent is later tapped again. "Another … nonland" excludes
 *   the Net itself and lands as targets.
 *
 * Back — Braided Quipu (Artifact):
 *   "{3}{U}, {T}: Draw a card for each artifact you control, then put this artifact into
 *    its owner's library third from the top." — the tuck reverts the DFC to its front
 *   face in the library (CR 712.8a).
 */
class BraidedNetScenarioTest : FunSpec({

    // Local test materials.
    val trinket = CardDefinition.artifact(
        name = "Test Trinket",
        manaCost = ManaCost.parse("{1}"),
        oracleText = ""
    )
    val bauble = CardDefinition.artifact(
        name = "Test Bauble",
        manaCost = ManaCost.parse("{1}"),
        oracleText = ""
    )
    val bear = CardDefinition.creature(
        name = "Test Bear",
        manaCost = ManaCost.parse("{1}{G}"),
        subtypes = setOf(Subtype("Bear")),
        power = 2,
        toughness = 2,
        oracleText = ""
    )

    // A lock victim with three activated abilities whose activatability separates the
    // effects under test: a non-mana ability that does NOT need the {T} cost (so only the
    // lock — not the tap itself — can forbid it), a {T} non-mana ability (to re-tap the
    // permanent after it untaps for the one-way check), and a non-tap *mana* ability (the
    // printed lock has no mana-ability carve-out, so it must vanish too).
    val gadget = card("Test Gadget") {
        manaCost = "{2}"
        typeLine = "Artifact"
        oracleText = "{1}: Draw a card.\n{T}: Draw a card.\nSacrifice this artifact: Add {U}."
        activatedAbility {
            cost = Costs.Mana("{1}")
            effect = Effects.DrawCards(1)
            description = "{1}: Draw a card."
        }
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.DrawCards(1)
            description = "{T}: Draw a card."
        }
        activatedAbility {
            cost = Costs.SacrificeSelf
            effect = AddManaEffect(Color.BLUE)
            manaAbility = true
            timing = TimingRule.ManaAbility
        }
    }
    val gadgetDrawForManaId = gadget.activatedAbilities[0].id
    val gadgetDrawForTapId = gadget.activatedAbilities[1].id
    val gadgetSacForManaId = gadget.activatedAbilities[2].id

    fun setup(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(BraidedNet, trinket, bauble, bear, gadget))
        driver.initMirrorMatch(
            deck = Deck.of("Island" to 40),
            skipMulligans = true
        )
        return driver
    }

    // The front face's two activated abilities: the printed tap-and-lock ability (the only one
    // with a target requirement) and the Craft (no targets).
    fun tapAbilityId() = BraidedNet.activatedAbilities.single { it.targetRequirements.isNotEmpty() }.id
    fun craftAbilityId() = BraidedNet.activatedAbilities.single { it.targetRequirements.isEmpty() }.id

    // The back face's single activated ability: draw per artifact, then self-tuck.
    fun quipuAbilityId() = BraidedNet.backFace!!.activatedAbilities.single().id

    fun netCounters(driver: GameTestDriver, id: EntityId): Int =
        driver.state.getEntity(id)?.get<CountersComponent>()?.getCount(CounterType.NET) ?: 0

    test("enters with three net counters when cast (replacement effect)") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val netCard = driver.putCardInHand(p1, "Braided Net")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.BLUE, 3)

        driver.castSpell(p1, netCard).error shouldBe null
        driver.bothPass()

        // On the battlefield as the front face, with the counters already applied —
        // a replacement effect, not a trigger, so they are present immediately.
        val container = driver.state.getEntity(netCard)
        container.shouldNotBeNull()
        container.get<CardComponent>()!!.name shouldBe "Braided Net"
        driver.state.getZone(ZoneKey(p1, Zone.BATTLEFIELD)).shouldContain(netCard)
        netCounters(driver, netCard) shouldBe 3
    }

    test("craft with exactly one artifact: exiles the material and returns as Braided Quipu") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val net = driver.putPermanentOnBattlefield(p1, "Braided Net")
        val material = driver.putPermanentOnBattlefield(p1, "Test Trinket")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.BLUE, 2)

        val result = driver.submit(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(material))
            )
        )
        result.error shouldBe null

        // Resolve the craft ability on the stack.
        driver.bothPass()

        // Material exiled.
        driver.state.getZone(ZoneKey(p1, Zone.EXILE)).shouldContain(material)

        // Source returned to the battlefield as the back face.
        val container = driver.state.getEntity(net)
        container.shouldNotBeNull()
        val card = container.get<CardComponent>()
        card.shouldNotBeNull()
        card.name shouldBe "Braided Quipu"
        card.typeLine.cardTypes shouldBe setOf(CardType.ARTIFACT)

        val dfc = container.get<DoubleFacedComponent>()
        dfc.shouldNotBeNull()
        dfc.currentFace shouldBe DoubleFacedComponent.Face.BACK
    }

    test("Braided Quipu: draws a card per artifact you control, then tucks itself third from the top as Braided Net (CR 712.8a)") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val net = driver.putPermanentOnBattlefield(p1, "Braided Net")
        val material = driver.putPermanentOnBattlefield(p1, "Test Trinket")
        driver.putPermanentOnBattlefield(p1, "Test Bauble")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.BLUE, 2)

        // Craft first so the entity carries its DoubleFacedComponent (real transform flow).
        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(material))
            )
        )
        driver.bothPass()
        driver.state.getEntity(net)!!.get<CardComponent>()!!.name shouldBe "Braided Quipu"

        // Artifacts you control at resolution: Braided Quipu itself + Test Bauble = 2.
        val handBefore = driver.state.getZone(ZoneKey(p1, Zone.HAND)).size
        driver.giveMana(p1, Color.BLUE, 4)
        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = quipuAbilityId()
            )
        )
        driver.bothPass()

        // Drew one card per artifact controlled (2).
        val handAfter = driver.state.getZone(ZoneKey(p1, Zone.HAND)).size
        handAfter - handBefore shouldBe 2

        // Tucked third from the top of its owner's library (0-indexed position 2) — and per
        // CR 712.8a the card in the library is the front face, Braided Net.
        val library = driver.state.getZone(ZoneKey(p1, Zone.LIBRARY))
        library[2] shouldBe net

        val tucked = driver.state.getEntity(net)
        tucked.shouldNotBeNull()
        tucked.get<CardComponent>()!!.name shouldBe "Braided Net"
        tucked.get<DoubleFacedComponent>()!!.currentFace shouldBe DoubleFacedComponent.Face.FRONT
    }

    test("craft rejects a creature (non-artifact) material") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val net = driver.putPermanentOnBattlefield(p1, "Braided Net")
        val creature = driver.putCreatureOnBattlefield(p1, "Test Bear")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.giveMana(p1, Color.BLUE, 2)

        val result = driver.submit(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = craftAbilityId(),
                costPayment = AdditionalCostPayment(exiledCards = listOf(creature))
            )
        )
        result.isSuccess shouldBe false
    }

    // NOTE: over-count material rejection ("exactly one" craft supplied two artifacts) is
    // covered generically by CraftExactCountScenarioTest with the same artifact filter.

    test("tap ability: taps the target, removes a net counter, and locks all its activated abilities (mana abilities included) while it remains tapped") {
        val driver = setup()
        val p1 = driver.activePlayer!!
        val p2 = driver.getOpponent(p1)

        val net = driver.putPermanentOnBattlefield(p1, "Braided Net")
        driver.replaceState(driver.state.updateEntity(net) {
            it.with(CountersComponent(mapOf(CounterType.NET to 3)))
        })
        val victim = driver.putPermanentOnBattlefield(p2, "Test Gadget")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = tapAbilityId(),
                targets = listOf(ChosenTarget.Permanent(victim))
            )
        )
        driver.bothPass()

        // {T} + "Remove a net counter" paid; the target is tapped by the resolution.
        driver.isTapped(net) shouldBe true
        netCounters(driver, net) shouldBe 2
        driver.isTapped(victim) shouldBe true

        // Hand priority to the opponent (stack is empty, still Player 1's main phase).
        driver.passPriority(p1)
        driver.giveColorlessMana(p2, 1)

        // While the gadget remains tapped, none of its activated abilities are enumerable —
        // not the non-tap {1} ability, and not the sacrifice mana ability either.
        val lockedActions = driver.legalActions(p2)
            .filter { (it.action as? ActivateAbility)?.sourceId == victim }
        withClue("Locked permanent must have no activatable abilities in legal actions") {
            lockedActions shouldBe emptyList()
        }

        // Direct submission is rejected for both the non-mana and the mana ability.
        driver.submitExpectFailure(
            ActivateAbility(playerId = p2, sourceId = victim, abilityId = gadgetDrawForManaId)
        )
        driver.submitExpectFailure(
            ActivateAbility(playerId = p2, sourceId = victim, abilityId = gadgetSacForManaId)
        )
    }

    test("tap ability: the lock ends when the permanent untaps and does not return when it is tapped again (CR 611.2b one-way)") {
        val driver = setup()
        val p1 = driver.activePlayer!!
        val p2 = driver.getOpponent(p1)

        val net = driver.putPermanentOnBattlefield(p1, "Braided Net")
        driver.replaceState(driver.state.updateEntity(net) {
            it.with(CountersComponent(mapOf(CounterType.NET to 3)))
        })
        val victim = driver.putPermanentOnBattlefield(p2, "Test Gadget")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        driver.submitSuccess(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = tapAbilityId(),
                targets = listOf(ChosenTarget.Permanent(victim))
            )
        )
        driver.bothPass()
        driver.isTapped(victim) shouldBe true

        // Advance to the opponent's turn: their untap step untaps the gadget.
        driver.passPriorityUntil(Step.UPKEEP)
        driver.activePlayer shouldBe p2
        driver.isTapped(victim) shouldBe false

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // Unlocked: the gadget's abilities are enumerable again and actually work.
        driver.giveColorlessMana(p2, 1)
        val unlockedActions = driver.legalActions(p2)
            .filter { (it.action as? ActivateAbility)?.sourceId == victim }
        withClue("Untapped permanent's abilities must be enumerable again") {
            unlockedActions.isNotEmpty() shouldBe true
        }
        val handBefore = driver.getHand(p2).size
        driver.submitSuccess(
            ActivateAbility(playerId = p2, sourceId = victim, abilityId = gadgetDrawForManaId)
        )
        driver.bothPass()
        driver.getHand(p2).size shouldBe handBefore + 1

        // Re-tap the gadget by other means (its own {T} ability). The expired lock must NOT
        // come back: "for as long as" durations never restart (CR 611.2b).
        driver.submitSuccess(
            ActivateAbility(playerId = p2, sourceId = victim, abilityId = gadgetDrawForTapId)
        )
        driver.bothPass()
        driver.isTapped(victim) shouldBe true

        driver.giveColorlessMana(p2, 1)
        driver.submitSuccess(
            ActivateAbility(playerId = p2, sourceId = victim, abilityId = gadgetDrawForManaId)
        )
        driver.bothPass()
    }

    test("tap ability: Braided Net itself ('another') and lands are not legal targets") {
        val driver = setup()
        val p1 = driver.activePlayer!!

        val net = driver.putPermanentOnBattlefield(p1, "Braided Net")
        driver.replaceState(driver.state.updateEntity(net) {
            it.with(CountersComponent(mapOf(CounterType.NET to 3)))
        })
        val island = driver.putLandOnBattlefield(p1, "Island")
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // "another target nonland permanent" — the Net itself is excluded…
        driver.submitExpectFailure(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = tapAbilityId(),
                targets = listOf(ChosenTarget.Permanent(net))
            )
        )
        // …and so is a land.
        driver.submitExpectFailure(
            ActivateAbility(
                playerId = p1,
                sourceId = net,
                abilityId = tapAbilityId(),
                targets = listOf(ChosenTarget.Permanent(island))
            )
        )

        // The rejected activations must not have paid any costs.
        driver.isTapped(net) shouldBe false
        netCounters(driver, net) shouldBe 3
    }
})
