package com.wingedsheep.gameserver.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "game")
data class GameProperties(
    val handSmoother: HandSmootherProperties = HandSmootherProperties(),
    val sets: SetsProperties = SetsProperties(),
    val admin: AdminProperties = AdminProperties(),
    val ai: AiProperties = AiProperties(),
    val magezero: MageZeroProperties = MageZeroProperties(),
    val debugMode: Boolean = false
)

data class HandSmootherProperties(
    val enabled: Boolean = true,
    val candidates: Int = 3
)

/**
 * Set enablement is configured by set code (e.g. "EOE", "DOM").
 *
 * - All sets are enabled by default.
 * - Codes in [disabledByDefault] are off unless explicitly enabled in [enabled].
 * - Codes in [enabled] override [disabledByDefault].
 *
 * Example application.yml:
 * ```
 * game:
 *   sets:
 *     enabled:
 *       EOE: true
 * ```
 */
data class SetsProperties(
    val disabledByDefault: Set<String> = setOf("DOM", "EOE"),
    val enabled: Map<String, Boolean> = emptyMap(),
) {
    fun isEnabled(setCode: String): Boolean {
        val key = setCode.uppercase()
        enabled[key]?.let { return it }
        enabled[setCode]?.let { return it }
        return disabledByDefault.none { it.equals(setCode, ignoreCase = true) }
    }
}

data class AdminProperties(
    val password: String = ""
)

data class AiProperties(
    val enabled: Boolean = false,
    /** AI mode: "engine" (built-in rules engine AI, default) or "llm" (LLM-based AI via API). */
    val mode: String = "engine",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    val openRouterApiKey: String = "",
    val model: String = "qwen/qwen3.6-plus:free",
    val deckbuildingModel: String = "",
    val reasoningEffort: String = "low",
    val maxRetries: Int = 2,
    val timeoutMs: Long = 300000,
    val thinkingDelayMs: Long = 500
) {
    /** Returns the model to use for deckbuilding — falls back to the gameplay model if not set. */
    val effectiveDeckbuildingModel: String get() = deckbuildingModel.ifBlank { model }

    /** Returns the effective API key — prefers [apiKey], falls back to [openRouterApiKey] for backward compatibility. */
    val effectiveApiKey: String get() = apiKey.ifBlank { openRouterApiKey }

    /** Whether we're using the built-in engine AI (no API key required). */
    val isEngineMode: Boolean get() = mode.equals("engine", ignoreCase = true)

    /** Whether we're using the LLM-based AI. */
    val isLlmMode: Boolean get() = mode.equals("llm", ignoreCase = true)

    /** Whether we're using the MageZero agent service. */
    val isMageZeroMode: Boolean get() = mode.equals("magezero", ignoreCase = true)
}

data class MageZeroProperties(
    /** URL of the Python agent service (agent_service.py). */
    val agentUrl: String = "http://127.0.0.1:5005",
    /** Timeout in seconds for requests to the agent service. */
    val timeoutSeconds: Long = 10L,
)
