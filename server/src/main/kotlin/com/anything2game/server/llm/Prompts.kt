package com.anything2game.server.llm

import com.anything2game.contract.SourceChunk

/** A prompt split into its system and user parts, plus the template name that produced it (the recorded `promptVersion`). */
data class RenderedPrompt(val system: String, val user: String, val version: String)

/**
 * Versioned prompt files under `resources/prompts/<name>.md`. A file is `system text`, a line `=== USER ===`, `user text`.
 * Placeholders are `{{name}}`. Rendering is strict: a placeholder without a value, or a value without a placeholder,
 * is a programming error and throws, so a prompt can never go out with a hole in it.
 */
object PromptTemplates {
    const val USER_MARKER = "=== USER ==="
    private val placeholder = Regex("\\{\\{([a-z_]+)}}")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    val ALL = listOf(
        "plan.v1", "level.v1", "repair.v1", "ask.v1",
        "frame-case.v1", "frame-escape.v1", "frame-rookie.v1", "frame-companion.v1",
    )

    fun raw(name: String): String = cache.getOrPut(name) {
        val stream = PromptTemplates::class.java.getResourceAsStream("/prompts/$name.md")
            ?: error("prompt template '$name' not found")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun placeholders(name: String): Set<String> = placeholder.findAll(raw(name)).map { it.groupValues[1] }.toSet()

    fun render(name: String, vars: Map<String, String>): RenderedPrompt {
        val template = raw(name)
        val needed = placeholders(name)
        require(vars.keys == needed) {
            "prompt '$name': missing ${needed - vars.keys}, unused ${vars.keys - needed}"
        }
        val parts = template.split("\n$USER_MARKER\n")
        require(parts.size == 2) { "prompt '$name' must contain exactly one $USER_MARKER line" }
        // Single pass per part: substituted values are never scanned again, so document text containing "{{x}}" or
        // the marker line stays inert.
        fun fill(part: String) = placeholder.replace(part) { vars.getValue(it.groupValues[1]) }
        return RenderedPrompt(system = fill(parts[0]).trim(), user = fill(parts[1]).trim(), version = name)
    }
}

/** The text format in which untrusted document content enters a prompt, and its inverse (used by the fake client). */
object PromptFormat {
    private val reserved = Regex("(?i)<(/?)(document|chunk|question|package|plan_level|original_task|previous_output|unit)\\b")
    private val chunkRegex = Regex("<chunk id=\"([^\"]+)\" page=\"(\\d+)\" label=\"([^\"]*)\">\\n(.*?)\\n</chunk>", RegexOption.DOT_MATCHES_ALL)

    /** Document text cannot close or open our delimiters: tag-like sequences are defused (`<` becomes `‹`). */
    fun defuse(text: String): String = reserved.replace(text) { "‹" + it.groupValues[1] + it.groupValues[2] }

    private val defused = Regex("‹(/?)(document|chunk|question|package|plan_level|original_task|previous_output|unit)\\b", RegexOption.IGNORE_CASE)

    /** Inverse of [defuse], applied to model output so that quotes copied from a defused prompt match the real chunk text again. */
    fun restore(text: String): String = if ('‹' !in text) text else defused.replace(text) { "<" + it.groupValues[1] + it.groupValues[2] }

    fun documentBlock(chunks: List<SourceChunk>): String = buildString {
        append("<document>\n")
        for (c in chunks) {
            append("<chunk id=\"").append(c.chunkId).append("\" page=\"").append(c.pageIndex)
                .append("\" label=\"").append((c.pageLabel ?: "").replace("\"", "")).append("\">\n")
                .append(defuse(c.text)).append("\n</chunk>\n")
        }
        append("</document>")
    }

    fun parseChunks(prompt: String): List<SourceChunk> {
        val doc = Regex("<document>(.*?)</document>", RegexOption.DOT_MATCHES_ALL).find(prompt)?.groupValues?.get(1) ?: return emptyList()
        return chunkRegex.findAll(doc).map { m ->
            SourceChunk(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { null }, m.groupValues[4])
        }.toList()
    }

    fun tag(name: String, content: String): String = "<$name>\n${defuse(content)}\n</$name>"

    fun tagContent(prompt: String, name: String): String? =
        Regex("<$name>\\n?(.*?)\\n?</$name>", RegexOption.DOT_MATCHES_ALL).find(prompt)?.groupValues?.get(1)
}
