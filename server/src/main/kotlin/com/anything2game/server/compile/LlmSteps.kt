package com.anything2game.server.compile

import com.anything2game.contract.ErrorCodes
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.LlmException
import com.anything2game.server.llm.LlmRequest
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import com.anything2game.server.llm.RenderedPrompt

/** Model calls shared by all nodes: provider errors become [GenerationException]s, and there is one repair routine. */
object LlmSteps {
    suspend fun call(client: LlmClient, prompt: RenderedPrompt, maxTokens: Int, label: String): String = try {
        PromptFormat.restore(client.completeJson(LlmRequest(prompt.system, prompt.user, maxTokens, label)))
    } catch (e: LlmException) {
        throw when (e.kind) {
            LlmException.Kind.TIMEOUT -> GenerationException(ErrorCodes.MODEL_TIMEOUT, "The model timed out.", retryable = true)
            LlmException.Kind.REFUSED -> GenerationException(ErrorCodes.GENERATION_FAILED, "The model declined this material.", retryable = false)
            else -> GenerationException(ErrorCodes.GENERATION_FAILED, "The model call failed.", retryable = e.retryable)
        }
    }

    /** The one repair call: the earlier output, the issue list and the original task go back to the model. */
    suspend fun repair(client: LlmClient, original: RenderedPrompt, previous: String, issues: List<String>, maxTokens: Int, label: String): String {
        val prompt = PromptTemplates.render(
            "repair.v1",
            mapOf(
                "original_system" to original.system,
                "original_user" to original.user,
                "previous_output" to previous.take(60_000),
                "issues" to issues.take(40).joinToString("\n") { "- $it" },
            ),
        )
        return call(client, prompt, maxTokens, "repair:$label")
    }
}
