package com.wingedsheep.ai.magezero

import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.ai.llm.BottomCardsInfo
import com.wingedsheep.ai.llm.CardSummary
import com.wingedsheep.ai.llm.MulliganInfo
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = LoggerFactory.getLogger(MageZeroAiPlayerController::class.java)

/**
 * AI controller that delegates decisions to the MageZero agent service.
 *
 * The agent service (agent_service.py) runs as a separate Python process,
 * encodes the game state as sparse features, queries the MageZero inference
 * server, and returns the chosen action ID.
 *
 * Falls back to [fallback] on any error (network failure, timeout, bad response).
 */
class MageZeroAiPlayerController(
    private val agentUrl: String,
    private val playerId: EntityId,
    private val fallback: AiPlayerController,
    private val timeoutSeconds: Long = 10L,
) : AiPlayerController {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun chooseAction(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?,
        recentGameLog: List<String>,
    ): ActionResponse {
        if (legalActions.isEmpty()) return fallback.chooseAction(state, legalActions, pendingDecision, recentGameLog)

        // Auto-pass: only one action and it's not combat
        if (legalActions.size == 1 && pendingDecision == null) {
            val only = legalActions[0]
            if (only.actionType != "DeclareAttackers" && only.actionType != "DeclareBlockers") {
                return ActionResponse.SubmitAction(only.action)
            }
        }

        try {
            val actionId = requestDecision(state, legalActions, pendingDecision)
            if (actionId != null) {
                val chosen = legalActions.find { it.action.toString() == actionId }
                    ?: legalActions.find { actionId.contains(it.actionType) }
                if (chosen != null) {
                    logger.info("MageZero chose: {}", chosen.description)
                    return ActionResponse.SubmitAction(chosen.action)
                }
                // actionId is the index
                val idx = actionId.toIntOrNull()
                if (idx != null && idx in legalActions.indices) {
                    return ActionResponse.SubmitAction(legalActions[idx].action)
                }
            }
        } catch (e: Exception) {
            logger.warn("MageZero agent error, falling back: {}", e.message)
        }

        return fallback.chooseAction(state, legalActions, pendingDecision, recentGameLog)
    }

    private fun requestDecision(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?,
    ): String? {
        // Serialize the request body
        val stateJson = json.encodeToString(state)
        val actionsJson = buildLegalActionsJson(legalActions)
        val pendingJson = pendingDecision?.let { buildPendingDecisionJson(it) } ?: "null"

        val body = """
            {
              "state": $stateJson,
              "legalActions": $actionsJson,
              "pendingDecision": $pendingJson,
              "playerId": "${playerId.value}"
            }
        """.trimIndent()

        val req = HttpRequest.newBuilder(URI.create("$agentUrl/decide"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            logger.warn("agent /decide returned {}: {}", resp.statusCode(), resp.body())
            return null
        }

        val parsed = json.parseToJsonElement(resp.body())
        return (parsed as? kotlinx.serialization.json.JsonObject)
            ?.get("actionId")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    private fun buildLegalActionsJson(legalActions: List<LegalActionInfo>): String {
        val items = legalActions.mapIndexed { idx, la ->
            """{"actionId":"$idx","kind":"${la.actionType}","description":${json.encodeToString(la.description)},"affordable":${la.isAffordable},"isManaAbility":${la.isManaAbility}}"""
        }
        return "[${items.joinToString(",")}]"
    }

    private fun buildPendingDecisionJson(decision: PendingDecision): String {
        return """{"type":"${decision::class.simpleName}","prompt":${json.encodeToString(decision.prompt)}}"""
    }

    // ------------------------------------------------------------------
    // Mulligan / bottom / draft — delegate to fallback
    // ------------------------------------------------------------------

    override fun decideMulligan(mulliganMessage: MulliganInfo): Boolean =
        fallback.decideMulligan(mulliganMessage)

    override fun chooseBottomCards(message: BottomCardsInfo): List<EntityId> =
        fallback.chooseBottomCards(message)

    override fun setDeckList(deckList: Map<String, Int>, archetype: String?) {
        fallback.setDeckList(deckList, archetype)
    }

    override fun chooseDraftPick(
        pack: List<CardSummary>,
        pickedSoFar: List<CardSummary>,
        packNumber: Int,
        pickNumber: Int,
        picksRequired: Int,
        passDirection: String,
    ): List<String> = fallback.chooseDraftPick(pack, pickedSoFar, packNumber, pickNumber, picksRequired, passDirection)

    override fun chooseWinstonAction(
        pileCards: List<CardSummary>,
        pileIndex: Int,
        pileSizes: List<Int>,
        pickedSoFar: List<CardSummary>,
    ): Boolean = fallback.chooseWinstonAction(pileCards, pileIndex, pileSizes, pickedSoFar)

    override fun chooseGridDraftPick(
        grid: List<CardSummary?>,
        availableSelections: List<String>,
        pickedSoFar: List<CardSummary>,
    ): String = fallback.chooseGridDraftPick(grid, availableSelections, pickedSoFar)
}
