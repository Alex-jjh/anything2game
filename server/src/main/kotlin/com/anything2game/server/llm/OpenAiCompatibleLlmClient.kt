package com.anything2game.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Any OpenAI-compatible `chat/completions` endpoint (DeepSeek, Qwen/DashScope compatible mode, ...).
 * Sends `response_format: {"type":"json_object"}`; the prompt must therefore mention JSON (ours do).
 * The API key is only ever put in the Authorization header; it is not logged and not part of any exception message.
 */
class OpenAiCompatibleLlmClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMillis: Long,
) : LlmClient {
    override val modelId: String get() = model
    private val parser = Json { ignoreUnknownKeys = true }

    override suspend fun completeJson(request: LlmRequest): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", request.system) })
                add(buildJsonObject { put("role", "user"); put("content", request.user) })
            })
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("max_tokens", request.maxTokens)
            put("temperature", 0.3)
            put("stream", false)
        }
        val response = try {
            http.post("$baseUrl/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                timeout { requestTimeoutMillis = timeoutMillis }
            }
        } catch (e: HttpRequestTimeoutException) {
            throw LlmException(LlmException.Kind.TIMEOUT, "model request timed out (${request.label})")
        } catch (e: IOException) {
            throw LlmException(LlmException.Kind.UPSTREAM, "model request failed (${request.label}): ${e.javaClass.simpleName}")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // Provider error bodies can echo parts of the request; log only the status.
            val retryable = response.status.value == 429 || response.status.value >= 500
            throw LlmException(LlmException.Kind.UPSTREAM, "model returned HTTP ${response.status.value} (${request.label})", retryable)
        }
        return try {
            val choice = parser.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject
            if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length") {
                throw LlmException(LlmException.Kind.BAD_RESPONSE, "model output was cut off (${request.label})")
            }
            val content = choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            JsonText.extractObject(content)
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException(LlmException.Kind.BAD_RESPONSE, "unexpected model response shape (${request.label})")
        }
    }
}

internal fun newHttpClient(): HttpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
    install(HttpTimeout) { connectTimeoutMillis = 15_000 }
    expectSuccess = false
}
