package com.wingedsheep.gameserver.ai.benchmark

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.advisor.modules.BloomburrowAdvisorModule
import com.wingedsheep.ai.engine.advisor.modules.OnslaughtAdvisorModule
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.ai.engine.EngineAiPlayerController
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.LegalActionEnricher
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.mtg.sets.definitions.bloomburrow.BloomburrowSet
import com.wingedsheep.mtg.sets.definitions.duskmourn.DuskmournSet
import com.wingedsheep.mtg.sets.definitions.edgeofeternities.EdgeOfEternitiesSet
import com.wingedsheep.mtg.sets.definitions.spiderman.SpiderManSet
import com.wingedsheep.mtg.sets.definitions.wildsofeldraineset.WildsOfEldrainSet
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.Rarity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.measureTime

// Tournament data structures
data class TournamentDeck(
    val name: String,
    val deck: Deck,
    val source: String // "json" or "file"
)

data class TournamentMatchupResult(
    val deck1: String,
    val deck2: String,
    val deck1Wins: Int,
    val deck2Wins: Int,
    val draws: Int,
    val deck1PlayWins: Int, // wins when on play
    val deck1DrawWins: Int, // wins when on draw
    val deck2PlayWins: Int,
    val deck2DrawWins: Int,
    val totalGames: Int
)

data class DeckPerformance(
    val deckName: String,
    var totalWins: Int,
    var totalLosses: Int,
    var totalDraws: Int,
    var playWins: Int,
    var drawWins: Int,
    var winRate: Double,
    var playWinRate: Double,
    var drawWinRate: Double,
    val matchupResults: MutableMap<String, TournamentMatchupResult> = mutableMapOf()
)

data class TournamentSummary(
    val deckPerformances: Map<String, DeckPerformance>,
    val totalGames: Int,
    val totalMatchups: Int,
    val wallTime: kotlin.time.Duration
)

/**
 * AI Matchup Runner
 * 
 * Runs 100 games between two defined decks and tracks results with replay saving.
 * 
 * Usage:
 * ./gradlew :game-server:build
 * kotlin run-ai-matchup.kt --deck1=red_aggro --deck2=white_weenie --games=100 --save-replays
 */

@Serializable
data class DeckConfig(
    val name: String,
    val cards: Map<String, Int>
)

@Serializable
data class AllDecks(
    val decks: Map<String, DeckConfig>
)

data class GameResult(
    val id: Int, val turns: Int, val actions: Int, val durationMs: Long,
    val p1Life: Int, val p2Life: Int, val completed: Boolean,
    val winnerLabel: String, val drawReason: String = ""
)

data class MatchupResult(
    val totalGames: Int,
    val p1Wins: Int,
    val p2Wins: Int,
    val draws: Int,
    val completed: Int,
    val avgTurns: Double,
    val avgActions: Double,
    val avgDuration: Long,
    val p1WinRate: Double,
    val p2WinRate: Double,
    val drawRate: Double
)

data class GameRunResult(val result: GameResult, val log: String)

fun main(args: Array<String>) {
    val tournamentMode = args.contains("--tournament")
    
    if (tournamentMode) {
        runTournament(args)
    } else {
        runSingleMatchup(args)
    }
}

fun runSingleMatchup(args: Array<String>) {
    val deck1Name = args.find { it.startsWith("--deck1=") }?.substringAfter("=")
    val deck2Name = args.find { it.startsWith("--deck2=") }?.substringAfter("=")
    val deck1File = args.find { it.startsWith("--deck1-file=") }?.substringAfter("=")
    val deck2File = args.find { it.startsWith("--deck2-file=") }?.substringAfter("=")
    val numGames = args.find { it.startsWith("--games=") }?.substringAfter("=")?.toIntOrNull() ?: 100
    val saveReplays = args.contains("--save-replays")
    val maxTurns = args.find { it.startsWith("--max-turns=") }?.substringAfter("=")?.toIntOrNull() ?: 50

    // Validate deck input options
    if (deck1Name != null && deck1File != null) {
        println("ERROR: Cannot specify both --deck1 and --deck1-file")
        return
    }
    if (deck2Name != null && deck2File != null) {
        println("ERROR: Cannot specify both --deck2 and --deck2-file")
        return
    }
    if (deck1Name == null && deck1File == null) {
        println("ERROR: Must specify either --deck1 or --deck1-file")
        return
    }
    if (deck2Name == null && deck2File == null) {
        println("ERROR: Must specify either --deck2 or --deck2-file")
        return
    }

    // Display deck info
    val deck1Display = deck1Name ?: "File: $deck1File"
    val deck2Display = deck2Name ?: "File: $deck2File"
    println("=== AI MATCHUP RUNNER ===")
    println("Deck 1: $deck1Display")
    println("Deck 2: $deck2Display")
    println("Games: $numGames")
    println("Save replays: $saveReplays")
    println("Max turns: $maxTurns")
    println()

    // Setup card registry with all available sets
    val allCards = mutableListOf<CardDefinition>().apply {
        addAll(BloomburrowSet.allCards)
        addAll(BloomburrowSet.basicLands)
        addAll(DuskmournSet.allCards)
        addAll(EdgeOfEternitiesSet.allCards)
        addAll(SpiderManSet.allCards)
        addAll(WildsOfEldrainSet.allCards)
        // Add more sets as needed
    }
    val registry = CardRegistry().apply { register(allCards) }

    // Load decks
    val deck1 = if (deck1Name != null) {
        // Load from JSON
        val projectRoot = System.getProperty("user.dir") ?: "."
        val decksFile = File(projectRoot, "../test-decks.json")
        if (!decksFile.exists()) {
            println("ERROR: test-decks.json not found!")
            return
        }

        val json = Json { ignoreUnknownKeys = true }
        val allDecks = json.decodeFromString<Map<String, DeckConfig>>(decksFile.readText())
        val deck1Config = allDecks[deck1Name]
        
        if (deck1Config == null) {
            println("ERROR: Deck '$deck1Name' not found in test-decks.json")
            println("Available decks: ${allDecks.keys.joinToString(", ")}")
            return
        }
        createDeck(deck1Config, registry)
    } else {
        // Load from text file
        createDeckFromTextFile(deck1File!!, registry)
    }

    val deck2 = if (deck2Name != null) {
        // Load from JSON
        val projectRoot = System.getProperty("user.dir") ?: "."
        val decksFile = File(projectRoot, "../test-decks.json")
        if (!decksFile.exists()) {
            println("ERROR: test-decks.json not found!")
            return
        }

        val json = Json { ignoreUnknownKeys = true }
        val allDecks = json.decodeFromString<Map<String, DeckConfig>>(decksFile.readText())
        val deck2Config = allDecks[deck2Name]
        
        if (deck2Config == null) {
            println("ERROR: Deck '$deck2Name' not found in test-decks.json")
            println("Available decks: ${allDecks.keys.joinToString(", ")}")
            return
        }
        createDeck(deck2Config, registry)
    } else {
        // Load from text file
        createDeckFromTextFile(deck2File!!, registry)
    }

    // Setup output directory
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val deck1Short = deck1Name ?: deck1File?.substringBeforeLast(".") ?: "unknown"
    val deck2Short = deck2Name ?: deck2File?.substringBeforeLast(".") ?: "unknown"
    val outputDir = File("ai-matchup-${deck1Short}-vs-${deck2Short}-$timestamp")
    outputDir.mkdirs()
    
    val csvFile = File(outputDir, "results.csv")
    csvFile.writeText("game,first_player,turns,actions,duration_ms,winner,p1_life,p2_life,completed,draw_reason\n")

    println("Output directory: ${outputDir.absolutePath}")
    println()

    // Run games
    val results = mutableListOf<GameResult>()
    var deck1PlayWins = 0
    var deck1DrawWins = 0
    var deck2PlayWins = 0
    var deck2DrawWins = 0
    var deck1PlayLosses = 0
    var deck1DrawLosses = 0
    var deck2PlayLosses = 0
    var deck2DrawLosses = 0
    
    val wallTime = measureTime {
        for (gameId in 1..numGames) {
            val deck1GoesFirst = gameId % 2 == 1
            
            // If deck1 goes first, play deck1 as P1, otherwise play deck2 as P1
            val result = if (deck1GoesFirst) {
                playGame(registry, deck1, deck2, gameId, maxTurns, "P1")
            } else {
                playGame(registry, deck2, deck1, gameId, maxTurns, "P1")
            }
            results.add(result.result)
            
            // Track play/draw wins and losses
            when (result.result.winnerLabel) {
                "P1" -> {
                    if (deck1GoesFirst) {
                        // deck1 is P1 (on play) and wins, deck2 is P2 (on draw) and loses
                        deck1PlayWins++
                        deck2DrawLosses++
                    } else {
                        // deck2 is P1 (on play) and wins, deck1 is P2 (on draw) and loses
                        deck2PlayWins++
                        deck1DrawLosses++
                    }
                }
                "P2" -> {
                    if (deck1GoesFirst) {
                        // deck1 is P1 (on play) and loses, deck2 is P2 (on draw) and wins
                        deck1PlayLosses++
                        deck2DrawWins++
                    } else {
                        // deck2 is P1 (on play) and loses, deck1 is P2 (on draw) and wins
                        deck2PlayLosses++
                        deck1DrawWins++
                    }
                }
                "draw" -> {
                    // For draws, track draws for each deck in their respective position
                    if (deck1GoesFirst) {
                        // deck1 is on play, deck2 is on draw
                        // No win/loss tracking for draws (could add draw tracking if needed)
                    } else {
                        // deck2 is on play, deck1 is on draw
                        // No win/loss tracking for draws
                    }
                }
            }
            
            // Save replay if requested
            if (saveReplays) {
                File(outputDir, "game-$gameId.log").writeText(result.log)
            }
            
            // Write to CSV
            val firstPlayerLabel = if (deck1GoesFirst) "P1" else "P2"
            csvFile.appendText("${result.result.id},${firstPlayerLabel},${result.result.turns},${result.result.actions},${result.result.durationMs},${result.result.winnerLabel},${result.result.p1Life},${result.result.p2Life},${result.result.completed},${result.result.drawReason}\n")
            
            // Progress update
            if (gameId % 10 == 0) {
                val p1Wins = results.count { it.winnerLabel == "P1" }
                val p2Wins = results.count { it.winnerLabel == "P2" }
                val draws = results.count { it.winnerLabel == "draw" }
                println("[$gameId/$numGames] P1: $p1Wins | P2: $p2Wins | Draws: $draws")
            }
        }
    }

    // Calculate and display final statistics
    val matchupStats = calculateMatchupStats(results)
    displaySingleMatchupResults(deck1Display, deck2Display, matchupStats, deck1PlayWins, deck1DrawWins, deck1PlayLosses, deck1DrawLosses, deck2PlayWins, deck2DrawWins, deck2PlayLosses, deck2DrawLosses, wallTime, outputDir)
}

fun runTournament(args: Array<String>) {
    val deckPoolFile = args.find { it.startsWith("--deck-pool=") }?.substringAfter("=") ?: "tournament-decks.json"
    val gamesPerMatchup = args.find { it.startsWith("--games=") }?.substringAfter("=")?.toIntOrNull() ?: 50
    val saveReplays = args.contains("--save-replays")
    val maxTurns = args.find { it.startsWith("--max-turns=") }?.substringAfter("=")?.toIntOrNull() ?: 50
    
    println("=== TOURNAMENT MODE ===")
    println("Deck pool file: $deckPoolFile")
    println("Games per matchup: $gamesPerMatchup")
    println("Save replays: $saveReplays")
    println("Max turns: $maxTurns")
    println()

    // Setup card registry
    val allCards = mutableListOf<CardDefinition>().apply {
        addAll(BloomburrowSet.allCards)
        addAll(BloomburrowSet.basicLands)
        addAll(DuskmournSet.allCards)
        addAll(EdgeOfEternitiesSet.allCards)
        addAll(SpiderManSet.allCards)
        addAll(WildsOfEldrainSet.allCards)
    }
    val registry = CardRegistry().apply { register(allCards) }

    // Load deck pool
    val tournamentDecks = loadTournamentDeckPool(deckPoolFile, registry)
    if (tournamentDecks.isEmpty()) {
        println("ERROR: No valid decks found in deck pool!")
        return
    }
    
    println("Loaded ${tournamentDecks.size} decks:")
    tournamentDecks.forEach { deck ->
        println("  - ${deck.name} (${deck.source})")
    }
    println()

    // Setup output directory
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val outputDir = File("tournament-${timestamp}")
    outputDir.mkdirs()
    
    println("Output directory: ${outputDir.absolutePath}")
    println()

    // Run tournament
    val tournamentSummary = runTournamentMatches(tournamentDecks, gamesPerMatchup, maxTurns, saveReplays, outputDir, registry)
    
    // Display results
    displayTournamentResults(tournamentSummary, outputDir)
}

fun loadTournamentDeckPool(deckPoolFile: String, registry: CardRegistry): List<TournamentDeck> {
    val projectRoot = System.getProperty("user.dir") ?: "."
    val poolFile = File(projectRoot, "../$deckPoolFile")
    
    if (!poolFile.exists()) {
        println("ERROR: Deck pool file not found: ${poolFile.absolutePath}")
        return emptyList()
    }
    
    val decks = mutableListOf<TournamentDeck>()
    
    // Try to load as JSON first
    try {
        val json = Json { ignoreUnknownKeys = true }
        val poolConfig = json.decodeFromString<TournamentPoolConfig>(poolFile.readText())
        
        // Load JSON decks
        poolConfig.jsonDecks?.forEach { deckName ->
            val decksFile = File(projectRoot, "../test-decks.json")
            if (decksFile.exists()) {
                val allDecks = json.decodeFromString<Map<String, DeckConfig>>(decksFile.readText())
                val deckConfig = allDecks[deckName]
                if (deckConfig != null) {
                    val deck = createDeck(deckConfig, registry)
                    decks.add(TournamentDeck(deckName, deck, "json"))
                } else {
                    println("WARNING: JSON deck '$deckName' not found in test-decks.json")
                }
            }
        }
        
        // Load text file decks
        poolConfig.textFiles?.forEach { filename ->
            try {
                val deck = createDeckFromTextFile(filename, registry)
                decks.add(TournamentDeck(filename.substringBeforeLast("."), deck, "file"))
            } catch (e: Exception) {
                println("WARNING: Failed to load text deck '$filename': ${e.message}")
            }
        }
        
    } catch (e: Exception) {
        println("WARNING: Could not parse deck pool as JSON, trying simple format...")
        
        // Try simple format - each line is a deck name or file path
        val lines = poolFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val decksFile = File(projectRoot, "../test-decks.json")
        val allDecks = if (decksFile.exists()) {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<Map<String, DeckConfig>>(decksFile.readText())
        } else {
            emptyMap()
        }
        
        lines.forEach { line ->
            val trimmed = line.trim()
            if (allDecks.containsKey(trimmed)) {
                // JSON deck
                val deck = createDeck(allDecks[trimmed]!!, registry)
                decks.add(TournamentDeck(trimmed, deck, "json"))
            } else {
                // Try as text file
                try {
                    val deck = createDeckFromTextFile(trimmed, registry)
                    decks.add(TournamentDeck(trimmed.substringBeforeLast("."), deck, "file"))
                } catch (e: Exception) {
                    println("WARNING: Could not load '$trimmed' as JSON deck or text file")
                }
            }
        }
    }
    
    return decks
}

fun runTournamentMatches(
    decks: List<TournamentDeck>, 
    gamesPerMatchup: Int, 
    maxTurns: Int, 
    saveReplays: Boolean,
    outputDir: File,
    registry: CardRegistry
): TournamentSummary {
    val deckPerformances = mutableMapOf<String, DeckPerformance>()
    val allMatchups = mutableListOf<TournamentMatchupResult>()
    var totalGames = 0
    
    // Initialize deck performances
    decks.forEach { deck ->
        deckPerformances[deck.name] = DeckPerformance(
            deckName = deck.name,
            totalWins = 0,
            totalLosses = 0,
            totalDraws = 0,
            playWins = 0,
            drawWins = 0,
            winRate = 0.0,
            playWinRate = 0.0,
            drawWinRate = 0.0
        )
    }
    
    val wallTime = measureTime {
        // Run all matchups
        for (i in decks.indices) {
            for (j in i + 1 until decks.size) {
                val deck1 = decks[i]
                val deck2 = decks[j]
                
                println("Matchup: ${deck1.name} vs ${deck2.name}")
                
                val matchupResult = runMatchup(
                    deck1, deck2, gamesPerMatchup, maxTurns, saveReplays, 
                    outputDir, registry, totalGames + 1
                )
                
                allMatchups.add(matchupResult)
                totalGames += matchupResult.totalGames
                
                // Update deck performances
                updateDeckPerformances(deckPerformances, matchupResult)
                
                println("  Result: ${deck1.name} ${matchupResult.deck1Wins} - ${matchupResult.deck2Wins} ${deck2.name} (${matchupResult.draws} draws)")
                println()
            }
        }
    }
    
    // Calculate final statistics
    deckPerformances.values.forEach { perf ->
        val totalGames = perf.totalWins + perf.totalLosses + perf.totalDraws
        perf.winRate = if (totalGames > 0) perf.totalWins.toDouble() / totalGames * 100 else 0.0
        
        // Play games: games where this deck was on play (half of total games, rounded up)
        val playGames = (totalGames + 1) / 2
        perf.playWinRate = if (playGames > 0) perf.playWins.toDouble() / playGames * 100 else 0.0
        
        // Draw games: games where this deck was on draw (half of total games, rounded down)
        val drawGames = totalGames / 2
        perf.drawWinRate = if (drawGames > 0) perf.drawWins.toDouble() / drawGames * 100 else 0.0
    }
    
    return TournamentSummary(
        deckPerformances = deckPerformances,
        totalGames = totalGames,
        totalMatchups = allMatchups.size,
        wallTime = wallTime
    )
}

fun runMatchup(
    deck1: TournamentDeck,
    deck2: TournamentDeck,
    games: Int,
    maxTurns: Int,
    saveReplays: Boolean,
    outputDir: File,
    registry: CardRegistry,
    startGameId: Int
): TournamentMatchupResult {
    var deck1Wins = 0
    var deck2Wins = 0
    var draws = 0
    var deck1PlayWins = 0
    var deck1DrawWins = 0
    var deck2PlayWins = 0
    var deck2DrawWins = 0
    
    for (gameId in 0 until games) {
        val actualGameId = startGameId + gameId
        val deck1GoesFirst = gameId % 2 == 0
        
        // If deck1 goes first, play deck1 as P1, otherwise play deck2 as P1
        val result = if (deck1GoesFirst) {
            playGame(registry, deck1.deck, deck2.deck, actualGameId, maxTurns, "P1")
        } else {
            playGame(registry, deck2.deck, deck1.deck, actualGameId, maxTurns, "P1")
        }
        
        when (result.result.winnerLabel) {
            "P1" -> {
                if (deck1GoesFirst) {
                    deck1Wins++
                    deck1PlayWins++
                    deck2DrawWins++
                } else {
                    deck2Wins++
                    deck2PlayWins++
                    deck1DrawWins++
                }
            }
            "P2" -> {
                if (deck1GoesFirst) {
                    deck2Wins++
                    deck2PlayWins++
                    deck1DrawWins++
                } else {
                    deck1Wins++
                    deck1PlayWins++
                    deck2DrawWins++
                }
            }
            "draw" -> draws++
        }
        
        // Save replay if requested
        if (saveReplays) {
            val matchupDir = File(outputDir, "${deck1.name}-vs-${deck2.name}")
            matchupDir.mkdirs()
            File(matchupDir, "game-$actualGameId.log").writeText(result.log)
        }
    }
    
    return TournamentMatchupResult(
        deck1 = deck1.name,
        deck2 = deck2.name,
        deck1Wins = deck1Wins,
        deck2Wins = deck2Wins,
        draws = draws,
        deck1PlayWins = deck1PlayWins,
        deck1DrawWins = deck1DrawWins,
        deck2PlayWins = deck2PlayWins,
        deck2DrawWins = deck2DrawWins,
        totalGames = games
    )
}

fun updateDeckPerformances(performances: MutableMap<String, DeckPerformance>, matchup: TournamentMatchupResult) {
    val deck1Perf = performances[matchup.deck1]!!
    val deck2Perf = performances[matchup.deck2]!!
    
    // Update deck 1
    deck1Perf.totalWins += matchup.deck1Wins
    deck1Perf.totalLosses += matchup.deck2Wins
    deck1Perf.totalDraws += matchup.draws
    deck1Perf.playWins += matchup.deck1PlayWins
    deck1Perf.drawWins += matchup.deck1DrawWins
    deck1Perf.matchupResults[matchup.deck2] = matchup
    
    // Update deck 2
    deck2Perf.totalWins += matchup.deck2Wins
    deck2Perf.totalLosses += matchup.deck1Wins
    deck2Perf.totalDraws += matchup.draws
    deck2Perf.playWins += matchup.deck2PlayWins
    deck2Perf.drawWins += matchup.deck2DrawWins
    deck2Perf.matchupResults[matchup.deck1] = matchup
}

fun displayTournamentResults(summary: TournamentSummary, outputDir: File) {
    println()
    println("=== TOURNAMENT RESULTS ===")
    println("Total games: ${summary.totalGames}")
    println("Total matchups: ${summary.totalMatchups}")
    println("Wall time: ${summary.wallTime}")
    println()
    
    // Sort decks by win rate
    val sortedPerformances = summary.deckPerformances.values.sortedByDescending { it.winRate }
    
    println("=== DECK RANKINGS ===")
    println("Rank | Deck            | Win Rate | Total W-L-D | Play Rate | Draw Rate")
    println("-----|-----------------|----------|-------------|-----------|-----------")
    
    sortedPerformances.forEachIndexed { index, perf ->
        val rank = index + 1
        val deckName = perf.deckName.take(15).padEnd(15)
        val winRate = String.format("%.1f%%", perf.winRate).padStart(8)
        val record = "${perf.totalWins}-${perf.totalLosses}-${perf.totalDraws}".padStart(11)
        val playRate = String.format("%.1f%%", perf.playWinRate).padStart(9)
        val drawRate = String.format("%.1f%%", perf.drawWinRate).padStart(9)
        
        println("$rank".padStart(4) + " | $deckName | $winRate | $record | $playRate | $drawRate")
    }
    
    println()
    println("=== DETAILED MATCHUP RESULTS ===")
    sortedPerformances.forEach { perf ->
        println("${perf.deckName}:")
        println("  Overall: ${perf.totalWins}-${perf.totalLosses}-${perf.totalDraws} (${String.format("%.1f", perf.winRate)}%)")
        println("  On play: ${perf.playWins}-${perf.totalLosses - perf.drawWins} (${String.format("%.1f", perf.playWinRate)}%)")
        println("  On draw: ${perf.drawWins}-${perf.totalLosses - perf.playWins} (${String.format("%.1f", perf.drawWinRate)}%)")
        
        perf.matchupResults.values.sortedBy { it.deck2 }.forEach { matchup ->
            val opponent = if (matchup.deck1 == perf.deckName) matchup.deck2 else matchup.deck1
            val wins = if (matchup.deck1 == perf.deckName) matchup.deck1Wins else matchup.deck2Wins
            val losses = if (matchup.deck1 == perf.deckName) matchup.deck2Wins else matchup.deck1Wins
            val playWins = if (matchup.deck1 == perf.deckName) matchup.deck1PlayWins else matchup.deck2PlayWins
            val drawWins = if (matchup.deck1 == perf.deckName) matchup.deck1DrawWins else matchup.deck2DrawWins
            
            println("    vs $opponent: $wins-$losses (${matchup.draws} draws) | Play: $playWins-${losses - drawWins} | Draw: $drawWins-${losses - playWins}")
        }
        println()
    }
    
    // Save detailed results to files
    saveTournamentResults(summary, outputDir)
}

fun saveTournamentResults(summary: TournamentSummary, outputDir: File) {
    // Save CSV with detailed results
    val csvFile = File(outputDir, "tournament-results.csv")
    csvFile.writeText("deck,total_wins,total_losses,total_draws,win_rate,play_wins,draw_wins,play_win_rate,draw_win_rate\n")
    
    summary.deckPerformances.values.forEach { perf ->
        csvFile.appendText("${perf.deckName},${perf.totalWins},${perf.totalLosses},${perf.totalDraws},${String.format("%.2f", perf.winRate)},${perf.playWins},${perf.drawWins},${String.format("%.2f", perf.playWinRate)},${String.format("%.2f", perf.drawWinRate)}\n")
    }
    
    // Save matchup matrix
    val matrixFile = File(outputDir, "matchup-matrix.csv")
    val decks = summary.deckPerformances.keys.toList()
    matrixFile.writeText("deck," + decks.joinToString(",") + "\n")
    
    decks.forEach { deck1 ->
        val row = mutableListOf<String>()
        row.add(deck1)
        
        decks.forEach { deck2 ->
            if (deck1 == deck2) {
                row.add("-")
            } else {
                val perf = summary.deckPerformances[deck1]!!
                val matchup = perf.matchupResults[deck2]
                if (matchup != null) {
                    val wins = if (matchup.deck1 == deck1) matchup.deck1Wins else matchup.deck2Wins
                    val losses = if (matchup.deck1 == deck1) matchup.deck2Wins else matchup.deck1Wins
                    row.add("$wins-$losses")
                } else {
                    row.add("0-0")
                }
            }
        }
        matrixFile.appendText(row.joinToString(",") + "\n")
    }
    
    println("Detailed results saved to:")
    println("  - ${csvFile.absolutePath}")
    println("  - ${matrixFile.absolutePath}")
}

@Serializable
data class TournamentPoolConfig(
    val jsonDecks: List<String>? = null,
    val textFiles: List<String>? = null
)

fun createDeck(config: DeckConfig, registry: CardRegistry): Deck {
    val cardNames = mutableListOf<String>()
    config.cards.forEach { (name, count) ->
        repeat(count) { cardNames.add(name) }
    }
    return Deck(cardNames)
}

fun createDeckFromTextFile(filename: String, registry: CardRegistry): Deck {
    val projectRoot = System.getProperty("user.dir") ?: "."
    val deckFile = File(projectRoot, "../$filename")
    
    if (!deckFile.exists()) {
        throw IllegalArgumentException("Deck file not found: ${deckFile.absolutePath}")
    }
    
    val cardNames = mutableListOf<String>()
    val lines = deckFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    
    for (line in lines) {
        val trimmedLine = line.trim()
        if (trimmedLine.isEmpty()) continue
        
        // Parse format: "count card name"
        val parts = trimmedLine.split(" ", limit = 2)
        if (parts.size != 2) {
            println("WARNING: Invalid line format: '$trimmedLine', expected 'count card name'")
            continue
        }
        
        try {
            val count = parts[0].toInt()
            val cardName = parts[1].trim()
            
            if (count <= 0) {
                println("WARNING: Invalid count $count for card: $cardName")
                continue
            }
            
            repeat(count) { cardNames.add(cardName) }
        } catch (e: NumberFormatException) {
            println("WARNING: Invalid count format in line: '$trimmedLine'")
        }
    }
    
    if (cardNames.isEmpty()) {
        throw IllegalArgumentException("No valid cards found in deck file: $filename")
    }
    
    println("Loaded ${cardNames.size} cards from $filename")
    return Deck(cardNames)
}

fun playGame(
    registry: CardRegistry,
    deck1: Deck, deck2: Deck,
    gameId: Int, maxTurns: Int,
    firstPlayerLabel: String
): GameRunResult {
    val log = StringBuilder()
    log.appendLine("=== Game $gameId ===")
    log.appendLine("First player: $firstPlayerLabel")
    log.appendLine("Deck 1: ${deck1.cards.groupingBy { it }.eachCount().map { "${it.key}×${it.value}" }.joinToString(", ")}")
    log.appendLine("Deck 2: ${deck2.cards.groupingBy { it }.eachCount().map { "${it.key}×${it.value}" }.joinToString(", ")}")

    val processor = ActionProcessor(registry)
    val initializer = GameInitializer(registry)
    val enumerator = LegalActionEnumerator.create(registry)
    val stateTransformer = ClientStateTransformer(registry)
    val enricher = LegalActionEnricher(ManaSolver(registry), registry)

    val initResult = initializer.initializeGame(
        GameConfig(
            players = listOf(PlayerConfig("Player 1", deck1), PlayerConfig("Player 2", deck2)),
            skipMulligans = true, startingPlayerIndex = 0
        )
    )

    val p1 = initResult.state.turnOrder[0]
    val p2 = initResult.state.turnOrder[1]
    var state = initResult.state

    // Create AI controllers
    val p1Controller = EngineAiPlayerController(registry, p1) { state }
    val p2Controller = EngineAiPlayerController(registry, p2) { state }
    fun controllerFor(id: EntityId) = if (id == p1) p1Controller else p2Controller
    fun label(id: EntityId) = if (id == p1) "P1" else "P2"

    p1Controller.setDeckList(deck1.cards.groupingBy { it }.eachCount())
    p2Controller.setDeckList(deck2.cards.groupingBy { it }.eachCount())

    // Game log for AI context
    val recentGameLog = mutableListOf<String>()
    val maxLogSize = 30

    var turns = 0
    var actionCount = 0
    var drawReason = ""
    var lastProgressTurn = 0
    var lastProgressAction = 0

    val duration = measureTime {
        while (!state.gameOver && turns < maxTurns) {
            if (actionCount - lastProgressAction > 300 && turns == lastProgressTurn) {
                drawReason = "stuck(turn=$turns,step=${state.step.name})"
                log.appendLine("[STUCK] $drawReason")
                break
            }
            if (state.turnNumber > turns) {
                lastProgressTurn = turns
                lastProgressAction = actionCount
                turns = state.turnNumber
            }

            val decision = state.pendingDecision
            val priorityPlayer = state.priorityPlayerId

            if (decision == null && priorityPlayer == null) {
                drawReason = "noPriority(turn=$turns)"
                break
            }

            // Determine who needs to act and build their view
            val actingPlayer = decision?.playerId ?: priorityPlayer!!
            val controller = controllerFor(actingPlayer)
            val clientState = stateTransformer.transform(state, actingPlayer)
            val legalActions = if (decision == null) {
                enricher.enrich(enumerator.enumerate(state, actingPlayer), state, actingPlayer)
            } else {
                emptyList<LegalActionInfo>()
            }

            // Call the controller
            actionCount++
            val response = controller.chooseAction(clientState, legalActions, decision, recentGameLog.toList())

            // Convert to GameAction
            val gameAction = when (response) {
                is ActionResponse.SubmitAction -> response.action
                is ActionResponse.SubmitDecision -> SubmitDecision(response.playerId, response.response)
            }

            log.appendLine("T$turns [${state.step.name}] ${label(actingPlayer)}: ${describeAction(gameAction)}")

            // Execute through ActionProcessor
            val result = processor.process(state, gameAction).result
            if (result.error != null) {
                log.appendLine("  ERROR: ${result.error}")
                // Fallback: use engine AI
                val engineAi = AIPlayer.create(registry, actingPlayer,
                    listOf(BloomburrowAdvisorModule(), OnslaughtAdvisorModule()))
                val fallbackAction = if (decision != null) {
                    val engineResponse = engineAi.respondToDecision(state, decision)
                    log.appendLine("  *** ENGINE FALLBACK: ${label(actingPlayer)} failed ${decision::class.simpleName}, engine answered with ${engineResponse::class.simpleName} ***")
                    SubmitDecision(actingPlayer, engineResponse)
                } else {
                    log.appendLine("  *** ENGINE FALLBACK: ${label(actingPlayer)} action failed, passing priority ***")
                    PassPriority(actingPlayer)
                }
                val fallback = processor.process(state, fallbackAction).result
                if (fallback.error != null) {
                    log.appendLine("  FALLBACK FAILED: ${fallback.error}")
                    drawReason = "error(${result.error})"
                    break
                }
                accumulateLog(fallback.events, actingPlayer, recentGameLog, maxLogSize)
                state = fallback.state
            } else {
                accumulateLog(result.events, actingPlayer, recentGameLog, maxLogSize)
                logEvents(result.events, log)
                if (gameAction !is PassPriority) {
                    logBoardState(state, p1, p2, log)
                }
                state = result.state
            }
        }
        if (!state.gameOver && drawReason.isEmpty()) drawReason = "maxTurns($maxTurns)"
    }

    val p1Life = state.getEntity(p1)?.get<LifeTotalComponent>()?.life ?: 0
    val p2Life = state.getEntity(p2)?.get<LifeTotalComponent>()?.life ?: 0
    val winner = when {
        state.gameOver && state.winnerId == p1 -> "P1"
        state.gameOver && state.winnerId == p2 -> "P2"
        else -> "draw"
    }

    log.appendLine("\n=== Result ===")
    log.appendLine("Winner: $winner | P1: ${p1Life}hp | P2: ${p2Life}hp | Turns: $turns | Actions: $actionCount")
    if (drawReason.isNotEmpty()) log.appendLine("Draw reason: $drawReason")

    return GameRunResult(
        GameResult(gameId, turns, actionCount, duration.inWholeMilliseconds,
            p1Life, p2Life, state.gameOver, winner, drawReason),
        log.toString()
    )
}

fun calculateMatchupStats(results: List<GameResult>): MatchupResult {
    val totalGames = results.size
    val completed = results.count { it.completed }
    val p1Wins = results.count { it.winnerLabel == "P1" }
    val p2Wins = results.count { it.winnerLabel == "P2" }
    val draws = results.count { it.winnerLabel == "draw" }

    return MatchupResult(
        totalGames = totalGames,
        p1Wins = p1Wins,
        p2Wins = p2Wins,
        draws = draws,
        completed = completed,
        avgTurns = results.map { it.turns }.average(),
        avgActions = results.map { it.actions }.average(),
        avgDuration = results.map { it.durationMs }.average().toLong(),
        p1WinRate = if (totalGames > 0) p1Wins * 100.0 / totalGames else 0.0,
        p2WinRate = if (totalGames > 0) p2Wins * 100.0 / totalGames else 0.0,
        drawRate = if (totalGames > 0) draws * 100.0 / totalGames else 0.0
    )
}

fun displaySingleMatchupResults(
    deck1Name: String, 
    deck2Name: String, 
    stats: MatchupResult, 
    deck1PlayWins: Int, 
    deck1DrawWins: Int, 
    deck1PlayLosses: Int, 
    deck1DrawLosses: Int,
    deck2PlayWins: Int, 
    deck2DrawWins: Int, 
    deck2PlayLosses: Int, 
    deck2DrawLosses: Int,
    wallTime: kotlin.time.Duration, 
    outputDir: File
) {
    println()
    println("=== SINGLE MATCHUP RESULTS ===")
    println("Matchup: $deck1Name vs $deck2Name")
    println("Total games: ${stats.totalGames}")
    println("Completed: ${stats.completed} / ${stats.totalGames} (${if (stats.totalGames > 0) stats.completed * 100 / stats.totalGames else 0}%)")
    println("Wall time: ${wallTime}")
    println()
    
    // Calculate play/draw win rates
    val deck1PlayGames = (stats.totalGames + 1) / 2
    val deck1DrawGames = stats.totalGames / 2
    val deck2PlayGames = deck1DrawGames
    val deck2DrawGames = deck1PlayGames
    
    val deck1PlayWinRate = if (deck1PlayGames > 0) deck1PlayWins.toDouble() / deck1PlayGames * 100 else 0.0
    val deck1DrawWinRate = if (deck1DrawGames > 0) deck1DrawWins.toDouble() / deck1DrawGames * 100 else 0.0
    val deck2PlayWinRate = if (deck2PlayGames > 0) deck2PlayWins.toDouble() / deck2PlayGames * 100 else 0.0
    val deck2DrawWinRate = if (deck2DrawGames > 0) deck2DrawWins.toDouble() / deck2DrawGames * 100 else 0.0
    
    println("=== OVERALL RESULTS ===")
    println("$deck1Name wins: ${stats.p1Wins} (${String.format("%.1f", stats.p1WinRate)}%)")
    println("$deck2Name wins: ${stats.p2Wins} (${String.format("%.1f", stats.p2WinRate)}%)")
    println("Draws: ${stats.draws} (${String.format("%.1f", stats.drawRate)}%)")
    println()
    
    println("=== PLAY/DRAW ANALYSIS ===")
    println("$deck1Name:")
    println("  Overall: ${stats.p1Wins}-${stats.p2Wins}-${stats.draws} (${String.format("%.1f", stats.p1WinRate)}%)")
    println("  On play: $deck1PlayWins-$deck1PlayLosses (${String.format("%.1f", deck1PlayWinRate)}%)")
    println("  On draw: $deck1DrawWins-$deck1DrawLosses (${String.format("%.1f", deck1DrawWinRate)}%)")
    println("  Total: ${deck1PlayWins + deck1DrawWins}-${deck1PlayLosses + deck1DrawLosses}")
    println()
    println("$deck2Name:")
    println("  Overall: ${stats.p2Wins}-${stats.p1Wins}-${stats.draws} (${String.format("%.1f", stats.p2WinRate)}%)")
    println("  On play: $deck2PlayWins-$deck2PlayLosses (${String.format("%.1f", deck2PlayWinRate)}%)")
    println("  On draw: $deck2DrawWins-$deck2DrawLosses (${String.format("%.1f", deck2DrawWinRate)}%)")
    println("  Total: ${deck2PlayWins + deck2DrawWins}-${deck2PlayLosses + deck2DrawLosses}")
    println()
    
    println("=== PERFORMANCE METRICS ===")
    println("Average turns: ${String.format("%.1f", stats.avgTurns)}")
    println("Average actions: ${String.format("%.0f", stats.avgActions)}")
    println("Average duration: ${stats.avgDuration}ms")
    println()
    
    // First player advantage analysis
    val firstPlayerWins = (deck1PlayWins + deck2PlayWins)
    val secondPlayerWins = (deck1DrawWins + deck2DrawWins)
    val firstPlayerWinRate = if (stats.totalGames > 0) firstPlayerWins.toDouble() / stats.totalGames * 100 else 0.0
    
    println("=== FIRST PLAYER ADVANTAGE ===")
    println("First player wins: $firstPlayerWins (${String.format("%.1f", firstPlayerWinRate)}%)")
    println("Second player wins: $secondPlayerWins (${String.format("%.1f", if (stats.totalGames > 0) secondPlayerWins.toDouble() / stats.totalGames * 100 else 0.0)}%)")
    println("First player advantage: ${String.format("%+.1f", firstPlayerWinRate - 50.0)}%")
    println()
    
    // Save detailed summary to file
    saveSingleMatchupSummary(deck1Name, deck2Name, stats, deck1PlayWins, deck1DrawWins, deck1PlayLosses, deck1DrawLosses, deck2PlayWins, deck2DrawWins, deck2PlayLosses, deck2DrawLosses, outputDir)
    
    println("Results saved to: ${outputDir.absolutePath}")
}

fun saveSingleMatchupSummary(
    deck1Name: String, 
    deck2Name: String, 
    stats: MatchupResult, 
    deck1PlayWins: Int, 
    deck1DrawWins: Int, 
    deck1PlayLosses: Int, 
    deck1DrawLosses: Int,
    deck2PlayWins: Int, 
    deck2DrawWins: Int, 
    deck2PlayLosses: Int, 
    deck2DrawLosses: Int,
    outputDir: File
) {
    val summaryFile = File(outputDir, "matchup-summary.txt")
    summaryFile.writeText("=== SINGLE MATCHUP SUMMARY ===\n")
    summaryFile.appendText("Matchup: $deck1Name vs $deck2Name\n")
    summaryFile.appendText("Date: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}\n")
    summaryFile.appendText("Total games: ${stats.totalGames}\n")
    summaryFile.appendText("Completed: ${stats.completed} / ${stats.totalGames} (${if (stats.totalGames > 0) stats.completed * 100 / stats.totalGames else 0}%)\n\n")
    
    summaryFile.appendText("=== RESULTS ===\n")
    summaryFile.appendText("$deck1Name wins: ${stats.p1Wins} (${String.format("%.1f", stats.p1WinRate)}%)\n")
    summaryFile.appendText("$deck2Name wins: ${stats.p2Wins} (${String.format("%.1f", stats.p2WinRate)}%)\n")
    summaryFile.appendText("Draws: ${stats.draws} (${String.format("%.1f", stats.drawRate)}%)\n\n")
    
    // Calculate play/draw statistics
    val deck1PlayGames = (stats.totalGames + 1) / 2
    val deck1DrawGames = stats.totalGames / 2
    val deck2PlayGames = deck1DrawGames
    val deck2DrawGames = deck1PlayGames
    
    val deck1PlayWinRate = if (deck1PlayGames > 0) deck1PlayWins.toDouble() / deck1PlayGames * 100 else 0.0
    val deck1DrawWinRate = if (deck1DrawGames > 0) deck1DrawWins.toDouble() / deck1DrawGames * 100 else 0.0
    val deck2PlayWinRate = if (deck2PlayGames > 0) deck2PlayWins.toDouble() / deck2PlayGames * 100 else 0.0
    val deck2DrawWinRate = if (deck2DrawGames > 0) deck2DrawWins.toDouble() / deck2DrawGames * 100 else 0.0
    
    summaryFile.appendText("=== PLAY/DRAW ANALYSIS ===\n")
    summaryFile.appendText("$deck1Name:\n")
    summaryFile.appendText("  On play: $deck1PlayWins-$deck1PlayLosses (${String.format("%.1f", deck1PlayWinRate)}%)\n")
    summaryFile.appendText("  On draw: $deck1DrawWins-$deck1DrawLosses (${String.format("%.1f", deck1DrawWinRate)}%)\n")
    summaryFile.appendText("$deck2Name:\n")
    summaryFile.appendText("  On play: $deck2PlayWins-$deck2PlayLosses (${String.format("%.1f", deck2PlayWinRate)}%)\n")
    summaryFile.appendText("  On draw: $deck2DrawWins-$deck2DrawLosses (${String.format("%.1f", deck2DrawWinRate)}%)\n\n")
    
    summaryFile.appendText("=== PERFORMANCE METRICS ===\n")
    summaryFile.appendText("Average turns: ${String.format("%.1f", stats.avgTurns)}\n")
    summaryFile.appendText("Average actions: ${String.format("%.0f", stats.avgActions)}\n")
    summaryFile.appendText("Average duration: ${stats.avgDuration}ms\n\n")
    
    val firstPlayerWins = (deck1PlayWins + deck2PlayWins)
    val firstPlayerWinRate = if (stats.totalGames > 0) firstPlayerWins.toDouble() / stats.totalGames * 100 else 0.0
    
    summaryFile.appendText("=== FIRST PLAYER ADVANTAGE ===\n")
    summaryFile.appendText("First player wins: $firstPlayerWins (${String.format("%.1f", firstPlayerWinRate)}%)\n")
    summaryFile.appendText("First player advantage: ${String.format("%+.1f", firstPlayerWinRate - 50.0)}%\n")
}

fun displayResults(deck1Name: String, deck2Name: String, stats: MatchupResult, wallTime: kotlin.time.Duration, outputDir: File) {
    println()
    println("=== FINAL RESULTS ===")
    println("Matchup: $deck1Name vs $deck2Name")
    println("Total games: ${stats.totalGames}")
    println("Completed: ${stats.completed} / ${stats.totalGames} (${if (stats.totalGames > 0) stats.completed * 100 / stats.totalGames else 0}%)")
    println()
    println("$deck1Name wins: ${stats.p1Wins} (${String.format("%.1f", stats.p1WinRate)}%)")
    println("$deck2Name wins: ${stats.p2Wins} (${String.format("%.1f", stats.p2WinRate)}%)")
    println("Draws: ${stats.draws} (${String.format("%.1f", stats.drawRate)}%)")
    println()
    println("Average turns: ${String.format("%.1f", stats.avgTurns)}")
    println("Average actions: ${String.format("%.0f", stats.avgActions)}")
    println("Average duration: ${stats.avgDuration}ms")
    println("Wall time: ${wallTime.inWholeMilliseconds}ms")
    println()
    println("Results saved to: ${outputDir.absolutePath}")
}

// Helper functions copied from AIBenchmark
private fun accumulateLog(events: List<GameEvent>, viewingPlayer: EntityId, log: MutableList<String>, maxSize: Int) {
    val clientEvents = ClientEventTransformer.transform(events, viewingPlayer)
    for (event in clientEvents) {
        if (event is com.wingedsheep.engine.view.ClientEvent.PermanentTapped) continue
        if (event is com.wingedsheep.engine.view.ClientEvent.PermanentUntapped) continue
        if (event is com.wingedsheep.engine.view.ClientEvent.ManaAdded) continue
        log.add(event.description)
        if (log.size > maxSize) log.removeFirst()
    }
}

private fun describeAction(action: GameAction): String = when (action) {
    is CastSpell -> "CastSpell"
    is PassPriority -> "Pass"
    is PlayLand -> "PlayLand"
    is DeclareAttackers -> "DeclareAttackers(${action.attackers.size})"
    is DeclareBlockers -> "DeclareBlockers(${action.blockers.size})"
    is ActivateAbility -> "ActivateAbility"
    is SubmitDecision -> "Decision(${action.response::class.simpleName})"
    else -> action::class.simpleName ?: "Unknown"
}

private fun logEvents(events: List<GameEvent>, log: StringBuilder) {
    val clientEvents = ClientEventTransformer.transform(events, EntityId("spectator"))
    for (event in clientEvents) {
        if (event is com.wingedsheep.engine.view.ClientEvent.PermanentTapped) continue
        if (event is com.wingedsheep.engine.view.ClientEvent.PermanentUntapped) continue
        if (event is com.wingedsheep.engine.view.ClientEvent.ManaAdded) continue
        log.appendLine("  → ${event.description}")
    }
}

private fun logBoardState(state: GameState, p1: EntityId, p2: EntityId, log: StringBuilder) {
    val projected = state.projectedState
    fun boardSummary(playerId: EntityId): String {
        val creatures = projected.getBattlefieldControlledBy(playerId)
            .filter { projected.isCreature(it) }
            .map { eid ->
                val name = state.getEntity(eid)?.get<CardComponent>()?.name ?: "?"
                "${name} ${projected.getPower(eid) ?: 0}/${projected.getToughness(eid) ?: 0}"
            }
        return if (creatures.isEmpty()) "(empty)" else creatures.joinToString(", ")
    }
    val p1Life = state.getEntity(p1)?.get<LifeTotalComponent>()?.life ?: 0
    val p2Life = state.getEntity(p2)?.get<LifeTotalComponent>()?.life ?: 0
    log.appendLine("  State: P1=${p1Life}hp ${boardSummary(p1)} | P2=${p2Life}hp ${boardSummary(p2)}")
}
