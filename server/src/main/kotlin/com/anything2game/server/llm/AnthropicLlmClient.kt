package com.anything2game.server.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * Claude through the official Java SDK. Intended for development and evaluation runs (decision 011).
 * The SDK retries 429/5xx itself. A `refusal` stop reason is surfaced as a non-retryable [LlmException].
 */
class AnthropicLlmClient(apiKey: String, private val model: String, timeoutSeconds: Long, baseUrl: String? = null) : LlmClient {
    override val modelId: String get() = model

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    override suspend fun completeJson(request: LlmRequest): String = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxTokens.toLong())
            .system(request.system)
            .addUserMessage(request.user)
            .build()
        val message = try {
            client.messages().create(params)
        } catch (e: AnthropicException) {
            // The SDK's exception text can include response bodies; keep only the class name.
            val timeout = e.cause is SocketTimeoutException || e.cause is java.io.InterruptedIOException
            throw LlmException(
                if (timeout) LlmException.Kind.TIMEOUT else LlmException.Kind.UPSTREAM,
                "model request failed (${request.label}): ${e.javaClass.simpleName}",
            )
        } catch (e: IOException) {
            throw LlmException(LlmException.Kind.UPSTREAM, "model request failed (${request.label}): ${e.javaClass.simpleName}")
        }
        if (message.stopReason().map { it == StopReason.REFUSAL }.orElse(false)) {
            throw LlmException(LlmException.Kind.REFUSED, "the model declined this request (${request.label})", retryable = false)
        }
        if (message.stopReason().map { it == StopReason.MAX_TOKENS }.orElse(false)) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "model output was cut off (${request.label})")
        }
        val text = message.content().flatMap { block -> block.text().map { it.text() }.stream().toList() }.joinToString("")
        if (text.isBlank()) throw LlmException(LlmException.Kind.BAD_RESPONSE, "model returned no text (${request.label})")
        JsonText.extractObject(text)
    }
}
