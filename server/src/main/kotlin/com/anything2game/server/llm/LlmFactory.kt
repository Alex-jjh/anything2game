package com.anything2game.server.llm

import com.anything2game.server.config.Provider
import com.anything2game.server.config.ServerConfig
import io.ktor.client.HttpClient

/** Builds the per-tier clients from the configuration. Keys are read from the config object (which got them from the environment). */
object LlmFactory {
    fun router(config: ServerConfig): LlmRouter {
        val http: HttpClient by lazy { newHttpClient() }
        val timeoutMillis = config.llmTimeoutSeconds * 1000
        fun client(provider: Provider, flash: Boolean): LlmClient = when (provider) {
            Provider.FAKE -> FakeLlmClient()
            Provider.OPENAI -> OpenAiCompatibleLlmClient(
                http, config.openAiBaseUrl, config.openAiApiKey!!.reveal(),
                if (flash) config.openAiModelFlash else config.openAiModelStrong, timeoutMillis,
            )
            Provider.ANTHROPIC -> AnthropicLlmClient(config.anthropicApiKey!!.reveal(), config.anthropicModel, config.llmTimeoutSeconds)
        }
        return LlmRouter(
            compile = client(config.compileProvider, flash = false),
            frame = client(config.frameProvider, flash = false),
            flash = client(config.flashProvider, flash = true),
        )
    }
}
