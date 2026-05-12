package com.wingedsheep.gym.server.config

import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.mtg.sets.definitions.blb.BloomburrowSet
import com.wingedsheep.mtg.sets.definitions.blc.BloomburrowCommanderSet
import com.wingedsheep.mtg.sets.definitions.bro.BrothersWarSet
import com.wingedsheep.mtg.sets.definitions.dft.AetherdriftSet
import com.wingedsheep.mtg.sets.definitions.dom.DominariaSet
import com.wingedsheep.mtg.sets.definitions.dmu.DominariaUnitedSet
import com.wingedsheep.mtg.sets.definitions.dsk.DuskmournSet
import com.wingedsheep.mtg.sets.definitions.ecl.LorwynEclipsedSet
import com.wingedsheep.mtg.sets.definitions.eoe.EdgeOfEternitiesSet
import com.wingedsheep.mtg.sets.definitions.fdn.FoundationsSet
import com.wingedsheep.mtg.sets.definitions.fin.FinalFantasySet
import com.wingedsheep.mtg.sets.definitions.inr.InnistradRemasteredSet
import com.wingedsheep.mtg.sets.definitions.inv.InvasionSet
import com.wingedsheep.mtg.sets.definitions.ktk.KhansOfTarkirSet
import com.wingedsheep.mtg.sets.definitions.lci.LostCavernsOfIxalanSet
import com.wingedsheep.mtg.sets.definitions.lgn.LegionsSet
import com.wingedsheep.mtg.sets.definitions.mid.InnistradMidnightHuntSet
import com.wingedsheep.mtg.sets.definitions.mkm.MurdersAtKarlovManorSet
import com.wingedsheep.mtg.sets.definitions.mom.MarchOfTheMachineSet
import com.wingedsheep.mtg.sets.definitions.one.PhyrexiaAllWillBeOneSet
import com.wingedsheep.mtg.sets.definitions.ons.OnslaughtSet
import com.wingedsheep.mtg.sets.definitions.otj.OutlawsOfThunderJunctionSet
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.scg.ScourgeSet
import com.wingedsheep.mtg.sets.definitions.spm.SpiderManSet
import com.wingedsheep.mtg.sets.definitions.tdm.TarkirDragonstormSet
import com.wingedsheep.mtg.sets.definitions.tla.AvatarTheLastAirbenderSet
import com.wingedsheep.mtg.sets.definitions.vow.InnistradCrimsonVowSet
import com.wingedsheep.mtg.sets.definitions.woe.WildsOfEldrainSet
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires a [CardRegistry] and [MultiEnvService] as Spring singletons.
 *
 * Registers the same full set catalogue as AiMatchupRunner so any deck
 * that works in the game-server also works in the gym-server.
 */
@Configuration
class GymBeansConfig {

    @Bean
    fun cardRegistry(): CardRegistry = CardRegistry().apply {
        register(AetherdriftSet.cards)
        register(AvatarTheLastAirbenderSet.cards)
        register(BloomburrowSet.cards)
        register(BloomburrowSet.basicLands)
        register(BloomburrowCommanderSet.cards)
        register(BrothersWarSet.cards)
        register(DominariaSet.cards)
        register(DominariaUnitedSet.cards)
        register(DuskmournSet.cards)
        register(EdgeOfEternitiesSet.cards)
        register(FinalFantasySet.cards)
        register(FoundationsSet.cards)
        register(InnistradCrimsonVowSet.cards)
        register(InnistradMidnightHuntSet.cards)
        register(InnistradRemasteredSet.cards)
        register(InvasionSet.cards)
        register(KhansOfTarkirSet.cards)
        register(LegionsSet.cards)
        register(LorwynEclipsedSet.cards)
        register(LostCavernsOfIxalanSet.cards)
        register(MarchOfTheMachineSet.cards)
        register(MurdersAtKarlovManorSet.cards)
        register(OnslaughtSet.cards)
        register(OutlawsOfThunderJunctionSet.cards)
        register(PhyrexiaAllWillBeOneSet.cards)
        register(PortalSet.cards)
        register(ScourgeSet.cards)
        register(SpiderManSet.cards)
        register(TarkirDragonstormSet.cards)
        register(WildsOfEldrainSet.cards)
    }

    @Bean
    fun printingRegistry(cardRegistry: CardRegistry): PrintingRegistry = PrintingRegistry().apply {
        for (name in cardRegistry.allCardNames()) {
            cardRegistry.getCardsByName(name).forEach(::registerSynthesizedDefault)
        }
        register(BloomburrowSet.printings)
        register(PortalSet.printings)
    }

    @Bean
    fun boosterGenerator(): BoosterGenerator = BoosterGenerator(
        mapOf(
            BloomburrowSet.code to BoosterGenerator.SetConfig(
                setCode = BloomburrowSet.code,
                setName = BloomburrowSet.displayName,
                cards = BloomburrowSet.cards,
                basicLands = BloomburrowSet.basicLands
            )
        )
    )

    @Bean
    fun multiEnvService(
        cardRegistry: CardRegistry,
        boosterGenerator: BoosterGenerator
    ): MultiEnvService = MultiEnvService(cardRegistry, boosterGenerator)
}
