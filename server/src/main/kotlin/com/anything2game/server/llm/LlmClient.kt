package com.anything2game.server.llm

/** One model call. [label] names the pipeline step for logs and for the fake client; it never contains document text. */
data class LlmRequest(val system: String, val user: String, val maxTokens: Int, val label: String)

interface LlmClient {
    /** Identifier recorded in `generation.model` of everything this client produced. */
    val modelId: String

    /** Returns the raw JSON text of the model's answer (a JSON object, no markdown fences). */
    suspend fun completeJson(request: LlmRequest): String
}

class LlmException(val kind: Kind, message: String, val retryable: Boolean = kind != Kind.REFUSED) : RuntimeException(message) {
    enum class Kind { TIMEOUT, REFUSED, UPSTREAM, BAD_RESPONSE }
}

/** Which model class a step uses. */
enum class Tier { COMPILE, FRAME, FLASH }

class LlmRouter(private val compile: LlmClient, private val frame: LlmClient, private val flash: LlmClient) {
    fun client(tier: Tier): LlmClient = when (tier) {
        Tier.COMPILE -> compile
        Tier.FRAME -> frame
        Tier.FLASH -> flash
    }
}

object JsonText {
    /**
     * Models sometimes wrap JSON in markdown fences or add a sentence around it. Returns the outermost `{...}` object,
     * or the trimmed text if there is none (the caller's parser then reports the error).
     */
    fun extractObject(raw: String): String {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else text
    }
}
