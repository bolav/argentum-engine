package com.wingedsheep.mtg.sets.definitions.vow.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.exploit
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggerSpec
import com.wingedsheep.sdk.scripting.references.Player

/**
 * Skull Skaab
 * {U}{B}
 * Creature — Zombie
 * 2/2
 * Exploit (When this creature enters, you may sacrifice a creature.)
 * Whenever a creature you control exploits a nontoken creature, create a 2/2 black Zombie
 * creature token.
 *
 * Skull Skaab's exploit has **no** self-payoff ([exploit] with a null `onExploit`) — its payoff
 * is a *broadcast* watcher over every creature you control, so it's a separate hand-written
 * triggered ability keyed on the observable [EventPattern.ExploitedEvent] the exploit reflexive
 * emits:
 *  - [TriggerBinding.ANY] — "a creature you control", which includes Skull Skaab's own exploit
 *    (CR 702.110b makes no exception for the exploiter itself);
 *  - `player = Player.You` scopes the exploiter's controller to you;
 *  - `requireNontokenExploited = true` realizes "exploits a **nontoken** creature" off the
 *    sacrificed creature's last-known token-ness captured before it left the battlefield.
 */
val SkullSkaab = card("Skull Skaab") {
    manaCost = "{U}{B}"
    colorIdentity = "UB"
    typeLine = "Creature — Zombie"
    power = 2
    toughness = 2
    oracleText = "Exploit (When this creature enters, you may sacrifice a creature.)\n" +
        "Whenever a creature you control exploits a nontoken creature, create a 2/2 black Zombie " +
        "creature token."

    exploit()

    triggeredAbility {
        trigger = TriggerSpec(
            EventPattern.ExploitedEvent(player = Player.You, requireNontokenExploited = true),
            TriggerBinding.ANY
        )
        effect = Effects.CreateToken(
            power = 2,
            toughness = 2,
            colors = setOf(Color.BLACK),
            creatureTypes = setOf("Zombie"),
            imageUri = "https://cards.scryfall.io/normal/front/c/8/c84e21cd-079d-493f-ab8d-e62f16ec1581.jpg?1782739822"
        )
        description = "Whenever a creature you control exploits a nontoken creature, create a 2/2 " +
            "black Zombie creature token."
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "248"
        artist = "Nicholas Gregory"
        imageUri = "https://cards.scryfall.io/normal/front/2/6/268fd7e7-0105-4c39-a3ea-77ed32214ff3.jpg?1782703020"
    }
}
