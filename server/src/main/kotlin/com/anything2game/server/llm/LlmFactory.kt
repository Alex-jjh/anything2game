package com.anything2game.server.llm

import com.anything2game.server.config.Provider
import com.anything2game.server.config.ServerConfig
import io.ktor.client.HttpClient

/**
 * Builds the per-tier clients from the configuration ([ServerConfig.tier] merges the per-tier variables with the shared ones).
 * Keys are read from the config object (which got them from the environment). Tiers that point at the same endpoint share one HTTP client.
 */
object LlmFactory {
    fun router(config: ServerConfig): LlmRouter {
        val http: HttpClient by lazy { newHttpClient() }
        val timeoutMillis = config.llmTimeoutSeconds * 1000
        fun client(tier: Tier): LlmClient {
            val t = config.tier(tier)
            return when (t.provider) {
                Provider.FAKE -> FakeLlmClient()
                Provider.OPENAI -> OpenAiCompatibleLlmClient(http, t.baseUrl!!, t.apiKey!!.reveal(), t.model, timeoutMillis)
                Provider.ANTHROPIC -> AnthropicLlmClient(t.apiKey!!.reveal(), t.model, config.llmTimeoutSeconds, t.baseUrl)
            }
        }
        return LlmRouter(compile = client(Tier.COMPILE), frame = client(Tier.FRAME), flash = client(Tier.FLASH))
    }
}
