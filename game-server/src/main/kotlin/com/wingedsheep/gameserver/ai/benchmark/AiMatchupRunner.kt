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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.measureTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

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
    val wallTime = measureTime {
        for (gameId in 1..numGames) {
            val (gameA, gameB) = if (gameId % 2 == 1) {
                // Game A: deck1 first
                val gameA = playGame(registry, deck1, deck2, gameId * 2 - 1, maxTurns, "P1")
                val gameB = playGame(registry, deck2, deck1, gameId * 2, maxTurns, "P2")
                Pair(gameA, gameB)
            } else {
                // Game B: deck2 first (swap for fairness)
                val gameA = playGame(registry, deck2, deck1, gameId * 2 - 1, maxTurns, "P2")
                val gameB = playGame(registry, deck1, deck2, gameId * 2, maxTurns, "P1")
                Pair(gameA, gameB)
            }

            results.addAll(listOf(gameA.result, gameB.result))

            // Save to CSV
            for ((game, firstPlayer) in listOf(gameA.result to "P1", gameB.result to "P2")) {
                csvFile.appendText("${game.id},${firstPlayer},${game.turns},${game.actions},${game.durationMs},${game.winnerLabel},${game.p1Life},${game.p2Life},${game.completed},${game.drawReason}\n")
            }

            // Save replays if requested
            if (saveReplays) {
                File(outputDir, "game-${gameId * 2 - 1}.log").writeText(gameA.log)
                File(outputDir, "game-${gameId * 2}.log").writeText(gameB.log)
            }

            // Progress update
            if (gameId % 10 == 0 || gameId == numGames) {
                val p1Wins = results.count { it.winnerLabel == "P1" }
                val p2Wins = results.count { it.winnerLabel == "P2" }
                val draws = results.count { it.winnerLabel == "draw" }
                println("[$gameId/$numGames] P1: $p1Wins | P2: $p2Wins | Draws: $draws")
            }
        }
    }

    // Calculate and display final statistics
    val matchupStats = calculateMatchupStats(results)
    displayResults(deck1Display, deck2Display, matchupStats, wallTime, outputDir)
}

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
    
    if (stats.draws > 0) {
        println()
        println("Draw reasons:")
        val drawResults = mutableListOf<GameResult>()
        // This would need to be passed in to show detailed draw reasons
    }
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
