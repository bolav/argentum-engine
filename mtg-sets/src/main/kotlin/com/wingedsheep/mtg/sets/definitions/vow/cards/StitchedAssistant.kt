package com.wingedsheep.mtg.sets.definitions.vow.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.exploit
import com.wingedsheep.sdk.model.Rarity

/**
 * Stitched Assistant
 * {2}{U}
 * Creature — Zombie
 * 3/2
 * Exploit (When this creature enters, you may sacrifice a creature.)
 * When this creature exploits a creature, scry 1, then draw a card.
 *
 * The self-bound payoff is untargeted, so it's baked straight into the exploit reflexive
 * ([exploit]'s `onExploit`): on a real sacrifice the reflexive scries 1 then draws a card.
 * Because it runs from the reflexive established at ETB (source present) it still fires when
 * Stitched Assistant sacrifices *itself* — no gone-source detection needed (see [exploit]).
 */
val StitchedAssistant = card("Stitched Assistant") {
    manaCost = "{2}{U}"
    colorIdentity = "U"
    typeLine = "Creature — Zombie"
    power = 3
    toughness = 2
    oracleText = "Exploit (When this creature enters, you may sacrifice a creature.)\n" +
        "When this creature exploits a creature, scry 1, then draw a card. (To scry 1, look at " +
        "the top card of your library, then you may put that card on the bottom.)"

    exploit(
        onExploit = Effects.Composite(
            Effects.Scry(1),
            Effects.DrawCards(1)
        )
    )

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "81"
        artist = "Andrey Kuzinskiy"
        imageUri = "https://cards.scryfall.io/normal/front/c/1/c1debb8c-d5e0-49e5-ab27-2d50e6f9d8d2.jpg?1782703135"
    }
}
