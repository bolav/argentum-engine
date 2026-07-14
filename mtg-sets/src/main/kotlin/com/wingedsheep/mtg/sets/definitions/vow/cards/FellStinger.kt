package com.wingedsheep.mtg.sets.definitions.vow.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.exploit
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Fell Stinger
 * {2}{B}
 * Creature — Zombie Scorpion
 * 3/2
 * Deathtouch
 * Exploit (When this creature enters, you may sacrifice a creature.)
 * When this creature exploits a creature, target player draws two cards and loses 2 life.
 *
 * The self-bound payoff is targeted, so it rides the exploit reflexive with a target requirement
 * ([exploit]'s `onExploitTargets`). Per [exploit]/`ReflexiveTriggerEffect`, the target player is
 * chosen **after** the sacrifice resolves — matching "when this creature exploits a creature,
 * target player …". Both halves of the payoff resolve against that same chosen player
 * ([EffectTarget.ContextTarget] 0). Running from the reflexive means the payoff fires even when
 * Fell Stinger sacrifices itself (no gone-source problem).
 */
val FellStinger = card("Fell Stinger") {
    manaCost = "{2}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Zombie Scorpion"
    power = 3
    toughness = 2
    oracleText = "Deathtouch\n" +
        "Exploit (When this creature enters, you may sacrifice a creature.)\n" +
        "When this creature exploits a creature, target player draws two cards and loses 2 life."

    keywords(Keyword.DEATHTOUCH)

    exploit(
        onExploit = Effects.Composite(
            Effects.DrawCards(2, EffectTarget.ContextTarget(0)),
            Effects.LoseLife(2, EffectTarget.ContextTarget(0))
        ),
        onExploitTargets = listOf(Targets.Player)
    )

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "112"
        artist = "Lars Grant-West"
        imageUri = "https://cards.scryfall.io/normal/front/2/7/27347849-6c07-42c0-bee4-74f93d7ad511.jpg?1782703110"
    }
}
