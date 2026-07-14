package com.wingedsheep.mtg.sets.definitions.lci.cards

import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Filters
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.craft
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantTriggeredAbility
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.effects.ReflexiveTriggerEffect
import com.wingedsheep.sdk.scripting.effects.SelectTargetEffect
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject

/**
 * Dire Flail // Dire Blunderbuss (CR 702.167, The Lost Caverns of Ixalan, #145)
 * {R}
 * Artifact — Equipment // Artifact — Equipment
 *
 * Front face — Dire Flail ({R}, Artifact — Equipment)
 *   Equipped creature gets +2/+0.
 *   Equip {1}
 *   Craft with artifact {3}{R}{R} ({3}{R}{R}, Exile this artifact, Exile another artifact
 *   you control or an artifact card from your graveyard: Return this card transformed
 *   under its owner's control. Craft only as a sorcery.)
 *
 * Back face — Dire Blunderbuss (Artifact — Equipment)
 *   Equipped creature gets +3/+0 and has "Whenever this creature attacks, you may
 *   sacrifice an artifact other than Dire Blunderbuss. When you do, this creature deals
 *   damage equal to its power to target creature."
 *   Equip {1}
 *
 * Implementation:
 *  - Front: [ModifyStats] +2/+0 static scoped to [Filters.EquippedCreature] plus the
 *    canonical `equipAbility("{1}")` (sorcery-speed attach, CR 702.6). The craft line uses
 *    the `craft(...)` helper with [GameObjectFilter.Artifact] and `minCount = maxCount = 1`
 *    ("Craft with artifact" = exactly one artifact material, CR 702.167a-b); resolution
 *    returns this card transformed under its owner's control. Unlike Sovereign's
 *    Macuahuitl, the back face has no ETB attach trigger — it enters unattached.
 *  - Back: [ModifyStats] +3/+0 static, and the quoted attack ability is granted to the
 *    equipped creature via [GrantTriggeredAbility] + [Triggers.attacks] (SELF binding, the
 *    Pirate Hat idiom), so it lives on the creature and fires when that creature attacks.
 *    The ability body is a [ReflexiveTriggerEffect] (the Glorifier of Suffering /
 *    Thousand Moons Crackshot idiom): the optional action selects an artifact you control
 *    (excluding Dire Blunderbuss) and sacrifices it; only "when you do" does the reflexive
 *    part go on the stack, choose target creature, and deal damage equal to the equipped
 *    creature's power ([DynamicAmounts.sourcePower] — the granted ability's source is the
 *    equipped creature; the damage source defaults to it, matching "this creature deals").
 *
 * KNOWN APPROXIMATION — "an artifact other than Dire Blunderbuss":
 * By CR 201.5a the name in the granted ability refers only to the specific Equipment that
 * granted it. The engine, however, threads a `granterId` only into granted *activated*
 * abilities (`AbilityOnStack.granterId` / `EffectTarget.GrantingSource`); granted
 * *triggered* abilities resolve with the equipped creature as their source and no
 * reference to the granting Equipment, so an entity-based exclusion is not expressible
 * today. The exclusion is therefore modeled as a name filter
 * (`GameObjectFilter.notNamed("Dire Blunderbuss")`). Corner case this mis-handles: with a
 * second Dire Blunderbuss on the battlefield, the printed card lets you sacrifice the
 * *other* copy, but the name filter wrongly excludes it too. Accepted, documented corner —
 * fix properly by threading granterId through granted triggered abilities.
 */

private val DireFlailFront = card("Dire Flail") {
    manaCost = "{R}"
    colorIdentity = "R"
    typeLine = "Artifact — Equipment"
    oracleText = "Equipped creature gets +2/+0.\n" +
        "Equip {1}\n" +
        "Craft with artifact {3}{R}{R} ({3}{R}{R}, Exile this artifact, Exile another artifact you control or an artifact card from your graveyard: Return this card transformed under its owner's control. Craft only as a sorcery.)"

    // Equipped creature gets +2/+0.
    staticAbility {
        ability = ModifyStats(+2, +0, Filters.EquippedCreature)
    }

    equipAbility("{1}")

    // Craft with artifact {3}{R}{R} — exactly one artifact material.
    craft(
        filter = GameObjectFilter.Artifact,
        cost = "{3}{R}{R}",
        materialDescription = "artifact",
        minCount = 1,
        maxCount = 1
    )

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "145"
        artist = "Anthony Devine"
        imageUri = "https://cards.scryfall.io/normal/front/0/d/0d2d98ae-fe02-4a86-9e80-7b95e08de21c.jpg?1782694493"
    }
}

private val DireBlunderbuss = card("Dire Blunderbuss") {
    manaCost = ""
    colorIdentity = "R"
    typeLine = "Artifact — Equipment"
    oracleText = "Equipped creature gets +3/+0 and has \"Whenever this creature attacks, you may sacrifice an artifact other than Dire Blunderbuss. When you do, this creature deals damage equal to its power to target creature.\"\n" +
        "Equip {1}"

    // Equipped creature gets +3/+0.
    staticAbility {
        ability = ModifyStats(+3, +0, Filters.EquippedCreature)
    }

    // ... and has "Whenever this creature attacks, you may sacrifice an artifact other
    // than Dire Blunderbuss. When you do, this creature deals damage equal to its power
    // to target creature."
    staticAbility {
        ability = GrantTriggeredAbility(
            ability = TriggeredAbility.create(
                trigger = Triggers.attacks().event,
                binding = Triggers.attacks().binding,
                effect = ReflexiveTriggerEffect(
                    // "you may sacrifice an artifact other than Dire Blunderbuss" — a
                    // resolution-time choice of your own artifact (name-based exclusion of
                    // the granting Equipment; see the KDoc approximation note).
                    action = Effects.Composite(listOf(
                        SelectTargetEffect(
                            requirement = TargetObject(
                                filter = TargetFilter(
                                    GameObjectFilter.Artifact
                                        .youControl()
                                        .notNamed("Dire Blunderbuss")
                                )
                            ),
                            storeAs = "toSacrifice"
                        ),
                        Effects.SacrificeTarget(EffectTarget.PipelineTarget("toSacrifice"))
                    )),
                    optional = true,
                    // "When you do, this creature deals damage equal to its power to
                    // target creature." Source of the granted ability = the equipped
                    // creature, so sourcePower() is its (buffed) power and the damage
                    // source defaults to it.
                    reflexiveEffect = Effects.DealDamage(
                        DynamicAmounts.sourcePower(),
                        EffectTarget.ContextTarget(0)
                    ),
                    reflexiveTargetRequirements = listOf(Targets.Creature),
                    descriptionOverride = "You may sacrifice an artifact other than Dire Blunderbuss. " +
                        "When you do, this creature deals damage equal to its power to target creature."
                )
            ),
            filter = Filters.EquippedCreature
        )
    }

    equipAbility("{1}")

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "145"
        artist = "Anthony Devine"
        flavorText = "\"Thought you were out of my skull-bashin' range, didn't you?\""
        imageUri = "https://cards.scryfall.io/normal/back/0/d/0d2d98ae-fe02-4a86-9e80-7b95e08de21c.jpg?1782694493"
    }
}

val DireFlail: CardDefinition = CardDefinition.doubleFacedPermanent(
    frontFace = DireFlailFront,
    backFace = DireBlunderbuss
)
