package com.wingedsheep.mtg.sets.definitions.lci.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.craft
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.Chooser
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.effects.MoveType
import com.wingedsheep.sdk.scripting.effects.RevealHandEffect
import com.wingedsheep.sdk.scripting.effects.SelectFromCollectionEffect
import com.wingedsheep.sdk.scripting.effects.SelectionMode
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * Visage of Dread // Dread Osseosaur (CR 702.167, The Lost Caverns of Ixalan)
 * {1}{B}
 * Artifact // Creature — Dinosaur Skeleton Horror
 *
 * Front face — Visage of Dread ({1}{B}, Artifact)
 *   When this artifact enters, target opponent reveals their hand. You choose an
 *   artifact or creature card from it. That player discards that card.
 *   Craft with two creatures {5}{B}
 *
 * Back face — Dread Osseosaur (Creature — Dinosaur Skeleton Horror, 5/4)
 *   Menace
 *   Whenever this creature enters or attacks, you may mill two cards.
 *
 * Implementation:
 *  - The front-face ETB is the Divest / Thought-Stalker Warlock reveal-choose-discard
 *    pipeline: [RevealHandEffect] on the target opponent, [GatherCardsEffect] over their
 *    hand, [SelectFromCollectionEffect] (chooser = controller) restricted to
 *    [GameObjectFilter.CreatureOrArtifact], then [MoveCollectionEffect] with
 *    [MoveType.Discard] to that player's graveyard.
 *  - Craft (CR 702.167a-b) uses the `craft(...)` DSL helper with an exact-two material
 *    count (`minCount = 2, maxCount = 2`): the materials may be creatures you control
 *    and/or creature cards in your graveyard, in any mix.
 *  - "Whenever this creature enters or attacks" on the back face follows the repo's
 *    established idiom for that wording (Sentinel of the Nameless City, Queen's Bay
 *    Paladin): TWO triggered abilities — [Triggers.EntersBattlefield] and
 *    [Triggers.Attacks] — sharing the same `MayEffect(Patterns.Library.mill(2))`
 *    effect, which is functionally equivalent to a single or-trigger (there is no
 *    single enters-or-attacks TriggerSpec in the SDK).
 */

private val VisageOfDreadFront = card("Visage of Dread") {
    manaCost = "{1}{B}"
    colorIdentity = "B"
    typeLine = "Artifact"
    oracleText = "When this artifact enters, target opponent reveals their hand. You choose an artifact or creature card from it. That player discards that card.\n" +
        "Craft with two creatures {5}{B} ({5}{B}, Exile this artifact, Exile the two from among creatures you control and/or creature cards in your graveyard: Return this card transformed under its owner's control. Craft only as a sorcery.)"

    // ETB: target opponent reveals their hand; you choose an artifact or creature
    // card from it; that player discards that card.
    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        val opponent = target("opponent", Targets.Opponent)
        effect = Effects.Composite(
            listOf(
                RevealHandEffect(opponent),
                GatherCardsEffect(
                    source = CardSource.FromZone(Zone.HAND, Player.ContextPlayer(0)),
                    storeAs = "revealedHand"
                ),
                SelectFromCollectionEffect(
                    from = "revealedHand",
                    selection = SelectionMode.ChooseExactly(DynamicAmount.Fixed(1)),
                    chooser = Chooser.Controller,
                    filter = GameObjectFilter.CreatureOrArtifact,
                    storeSelected = "chosenCard",
                    prompt = "Choose an artifact or creature card to discard"
                ),
                MoveCollectionEffect(
                    from = "chosenCard",
                    destination = CardDestination.ToZone(Zone.GRAVEYARD, Player.ContextPlayer(0)),
                    moveType = MoveType.Discard
                )
            )
        )
    }

    craft(
        filter = GameObjectFilter.Creature,
        cost = "{5}{B}",
        materialDescription = "two creatures",
        minCount = 2,
        maxCount = 2
    )

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "129"
        artist = "David Auden Nash"
        imageUri = "https://cards.scryfall.io/normal/front/3/d/3d4b61c6-3e88-49f5-9e16-aa8a59653327.jpg?1782694508"
    }
}

private val DreadOsseosaur = card("Dread Osseosaur") {
    manaCost = ""
    colorIdentity = "B"
    typeLine = "Creature — Dinosaur Skeleton Horror"
    power = 5
    toughness = 4
    oracleText = "Menace\nWhenever this creature enters or attacks, you may mill two cards. (You may put the top two cards of your library into your graveyard.)"

    keywords(Keyword.MENACE)

    // "Whenever this creature enters or attacks" — two triggered abilities with the
    // same effect (the repo's established idiom for this wording).
    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        effect = MayEffect(Patterns.Library.mill(2))
    }

    triggeredAbility {
        trigger = Triggers.Attacks
        effect = MayEffect(Patterns.Library.mill(2))
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "129"
        artist = "David Auden Nash"
        flavorText = "\"It's not my fault! The glyphs for 'blessed' and 'cursed' look very similar!\"\n—Diego, Brazen Coalition translator"
        imageUri = "https://cards.scryfall.io/normal/back/3/d/3d4b61c6-3e88-49f5-9e16-aa8a59653327.jpg?1782694508"
    }
}

val VisageOfDread: CardDefinition = CardDefinition.doubleFacedPermanent(
    frontFace = VisageOfDreadFront,
    backFace = DreadOsseosaur
)
