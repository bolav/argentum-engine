package com.wingedsheep.mtg.sets.definitions.lci.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.craft
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * Jade Seedstones // Jadeheart Attendant (CR 702.167, The Lost Caverns of Ixalan)
 * {3}{G}
 * Artifact // Artifact Creature — Golem
 *
 * Front face — Jade Seedstones ({3}{G}, Artifact)
 *   When this artifact enters, distribute three +1/+1 counters among one, two, or
 *   three target creatures you control.
 *   Craft with creature {5}{G}{G} ({5}{G}{G}, Exile this artifact, Exile a creature
 *   you control or a creature card from your graveyard: Return this card transformed
 *   under its owner's control. Craft only as a sorcery.)
 *
 * Back face — Jadeheart Attendant (Artifact Creature — Golem, 7/7)
 *   When this creature enters, you gain life equal to the mana value of the exiled
 *   card used to craft it.
 *
 * Implementation: built from the engine's Craft primitives. The front face's ETB
 * trigger is the established distribute shape ([TargetCreature] with `count = 3,
 * minCount = 1` over creatures you control + [Effects.DistributeCountersAmongTargets]).
 * The `craft(...)` helper wires the activated ability with a
 * [com.wingedsheep.sdk.scripting.AbilityCost.Craft] material cost (exactly one creature:
 * `minCount = 1, maxCount = 1`) paired with the printed mana cost; resolution
 * ([com.wingedsheep.sdk.scripting.effects.ReturnSelfFromExileTransformedEffect]) returns
 * the source transformed and re-attaches the `CraftedFromExiledComponent` recording the
 * material. The back face's ETB gain-life reads
 * [DynamicAmount.CraftedMaterialsTotalManaValue] off that component — with exactly one
 * material this is precisely "the mana value of the exiled card used to craft it".
 */

private val JadeSeedstonesFront = card("Jade Seedstones") {
    manaCost = "{3}{G}"
    colorIdentity = "G"
    typeLine = "Artifact"
    oracleText = "When this artifact enters, distribute three +1/+1 counters among one, two, or three target creatures you control.\n" +
        "Craft with creature {5}{G}{G} ({5}{G}{G}, Exile this artifact, Exile a creature you control or a creature card from your graveyard: Return this card transformed under its owner's control. Craft only as a sorcery.)"

    // ETB: distribute three +1/+1 counters among one, two, or three target creatures you control.
    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        target = TargetCreature(count = 3, minCount = 1, filter = TargetFilter.CreatureYouControl)
        effect = Effects.DistributeCountersAmongTargets(totalCounters = 3)
    }

    // "Craft with creature" = exactly one creature material (CR 702.167a).
    craft(
        filter = GameObjectFilter.Creature,
        cost = "{5}{G}{G}",
        materialDescription = "creature",
        minCount = 1,
        maxCount = 1
    )

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "195"
        artist = "Alexandre Honoré"
        imageUri = "https://cards.scryfall.io/normal/front/b/b/bb95ffc2-ef35-49bf-8211-c5354d176051.jpg?1782694452"
    }
}

private val JadeheartAttendant = card("Jadeheart Attendant") {
    manaCost = ""
    colorIdentity = "G"
    typeLine = "Artifact Creature — Golem"
    power = 7
    toughness = 7
    oracleText = "When this creature enters, you gain life equal to the mana value of the exiled card used to craft it."

    // ETB: gain life equal to the mana value of the (exactly one) card exiled to craft it.
    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        effect = Effects.GainLife(DynamicAmount.CraftedMaterialsTotalManaValue)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "195"
        artist = "Alexandre Honoré"
        flavorText = "When the merfolk fled underground, they brought the life force of the jungle with them."
        imageUri = "https://cards.scryfall.io/normal/back/b/b/bb95ffc2-ef35-49bf-8211-c5354d176051.jpg?1782694452"
    }
}

val JadeSeedstones: CardDefinition = CardDefinition.doubleFacedPermanent(
    frontFace = JadeSeedstonesFront,
    backFace = JadeheartAttendant
)
