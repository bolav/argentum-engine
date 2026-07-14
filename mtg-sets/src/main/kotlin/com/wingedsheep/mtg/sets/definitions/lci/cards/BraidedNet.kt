package com.wingedsheep.mtg.sets.definitions.lci.cards

import com.wingedsheep.sdk.core.Counters
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.craft
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.EntersWithCounters
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.PreventActivatedAbilities
import com.wingedsheep.sdk.scripting.events.CounterTypeFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Braided Net // Braided Quipu (CR 702.167, The Lost Caverns of Ixalan #47)
 * {2}{U}
 * Artifact // Artifact
 *
 * Front face — Braided Net ({2}{U}, Artifact)
 *   This artifact enters with three net counters on it.
 *   {T}, Remove a net counter from this artifact: Tap another target nonland permanent.
 *   Its activated abilities can't be activated for as long as it remains tapped.
 *   Craft with artifact {1}{U}
 *
 * Back face — Braided Quipu (Artifact)
 *   {3}{U}, {T}: Draw a card for each artifact you control, then put this artifact into
 *   its owner's library third from the top.
 *
 * Implementation:
 *  - Enters-with-counters: [EntersWithCounters] replacement effect (`selfOnly = true`,
 *    `CounterTypeFilter.Named(Counters.NET)`, count 3) — applied as the Net enters, so it
 *    works on cast entry and any other battlefield entry (same shape as Explorer's Cache).
 *    `Counters.NET` / `CounterType.NET` were added for this card (passive named counter,
 *    same pattern as `fire` / `conqueror`).
 *  - Craft: the `craft(...)` DSL helper wires the activated ability with an
 *    [com.wingedsheep.sdk.scripting.AbilityCost.Craft] material cost (exactly one artifact:
 *    `minCount = maxCount = 1`, drawn from artifacts you control and/or artifact cards in
 *    your graveyard per CR 702.167b) plus the {1}{U} mana portion; resolution returns the
 *    card transformed via
 *    [com.wingedsheep.sdk.scripting.effects.ReturnSelfFromExileTransformedEffect].
 *    The printed craft line carries no reminder text — oracleText is verbatim.
 *  - Tap-and-lock ability: `Costs.Composite(Costs.Tap, Costs.RemoveCounterFromSelf(Counters.NET, 1))`
 *    with `Targets.OtherNonlandPermanent` ("another target nonland permanent" — excludes the
 *    Net itself and lands). Resolution taps the target, then grants it
 *    [PreventActivatedAbilities] scoped to the holder itself
 *    (`GameObjectFilter.Permanent.sourceItself()`) for
 *    [com.wingedsheep.sdk.scripting.Duration.WhileAffectedTapped]. The granted static is
 *    consulted by `CastPermissionUtils.isActivationPrevented` (the same read site as the
 *    printed Cursed Totem form), so it blocks mana and non-mana activated abilities alike —
 *    the printed line has no mana-ability carve-out. The duration is one-way per CR 611.2b:
 *    `EndedDurationExpiryCheck` removes the grant the moment the permanent untaps (or leaves
 *    the battlefield), so tapping it again later does not re-lock it.
 *  - Back-face ability: `Costs.Composite(Costs.Mana("{3}{U}"), Costs.Tap)`;
 *    `Effects.DrawCards(DynamicAmounts.battlefield(Player.You, GameObjectFilter.Artifact).count())`
 *    (the Quipu counts itself — it is still on the battlefield, tapped, while resolving),
 *    then `Effects.PutIntoLibraryNthFromTop(EffectTarget.Self, positionFromTop = 2)`
 *    (0-indexed: 2 = third from the top). The DFC goes to the library as its front face,
 *    Braided Net (CR 712.8a) — handled by the engine's zone-transition face reversion.
 */

private val BraidedNetFront = card("Braided Net") {
    manaCost = "{2}{U}"
    colorIdentity = "U"
    typeLine = "Artifact"
    oracleText = "This artifact enters with three net counters on it.\n" +
        "{T}, Remove a net counter from this artifact: Tap another target nonland permanent. Its activated abilities can't be activated for as long as it remains tapped.\n" +
        "Craft with artifact {1}{U}"

    // This artifact enters with three net counters on it.
    replacementEffect(
        EntersWithCounters(
            counterType = CounterTypeFilter.Named(Counters.NET),
            count = 3,
            selfOnly = true
        )
    )

    // {T}, Remove a net counter from this artifact: Tap another target nonland permanent.
    // Its activated abilities can't be activated for as long as it remains tapped.
    activatedAbility {
        cost = Costs.Composite(Costs.Tap, Costs.RemoveCounterFromSelf(Counters.NET, 1))
        val netted = target("nonland permanent to tap", Targets.OtherNonlandPermanent)
        effect = Effects.Composite(
            Effects.Tap(netted),
            // "Its activated abilities can't be activated …" — the grant is anchored to the
            // target, and the self-scoped filter locks the holder's own abilities (mana
            // abilities included; the printed line has no mana-ability carve-out). One-way
            // per CR 611.2b: ends for good when the permanent untaps.
            Effects.GrantStaticAbility(
                ability = PreventActivatedAbilities(GameObjectFilter.Permanent.sourceItself()),
                target = netted,
                duration = Duration.WhileAffectedTapped
            )
        )
        description = "{T}, Remove a net counter from this artifact: Tap another target " +
            "nonland permanent. Its activated abilities can't be activated for as long " +
            "as it remains tapped."
    }

    // Craft with artifact {1}{U} — exactly one artifact material.
    craft(
        filter = GameObjectFilter.Artifact,
        cost = "{1}{U}",
        materialDescription = "artifact",
        minCount = 1,
        maxCount = 1
    )

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "47"
        artist = "Diego Gisbert"
        imageUri = "https://cards.scryfall.io/normal/front/6/8/68a6ede0-6d57-4e29-9e3b-3569ab7f0bcd.jpg?1782694573"
    }
}

private val BraidedQuipu = card("Braided Quipu") {
    manaCost = ""
    colorIdentity = "U"
    typeLine = "Artifact"
    oracleText = "{3}{U}, {T}: Draw a card for each artifact you control, " +
        "then put this artifact into its owner's library third from the top."

    // {3}{U}, {T}: Draw a card for each artifact you control, then put this artifact
    // into its owner's library third from the top.
    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{3}{U}"), Costs.Tap)
        effect = Effects.Composite(
            Effects.DrawCards(
                DynamicAmounts.battlefield(Player.You, GameObjectFilter.Artifact).count()
            ),
            Effects.PutIntoLibraryNthFromTop(EffectTarget.Self, positionFromTop = 2)
        )
        description = "{3}{U}, {T}: Draw a card for each artifact you control, " +
            "then put this artifact into its owner's library third from the top."
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "47"
        artist = "Diego Gisbert"
        flavorText = "The Oltec repurpose objects out of reverence rather than scarcity, " +
            "believing them strengthened by the memory of each past function."
        imageUri = "https://cards.scryfall.io/normal/back/6/8/68a6ede0-6d57-4e29-9e3b-3569ab7f0bcd.jpg?1782694573"
    }
}

val BraidedNet: CardDefinition = CardDefinition.doubleFacedPermanent(
    frontFace = BraidedNetFront,
    backFace = BraidedQuipu
)
