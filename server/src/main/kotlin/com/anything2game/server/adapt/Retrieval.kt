package com.anything2game.server.adapt

import com.anything2game.contract.SourceChunk

/** Keyword retrieval over one document's chunks. Latin text by words, Chinese (and other CJK) by character bigrams. */
object Retrieval {
    private val stopWords = setOf(
        "the", "a", "an", "of", "to", "in", "on", "at", "for", "and", "or", "is", "are", "was", "were", "be", "been", "it", "its",
        "this", "that", "these", "those", "what", "which", "who", "whom", "how", "why", "when", "where", "do", "does", "did", "can",
        "could", "should", "would", "will", "with", "by", "as", "from", "about", "me", "my", "you", "your", "i", "we", "they", "he", "she",
        "not", "no", "so", "if", "than", "then", "there", "their", "has", "have", "had", "tell", "explain", "please",
    )
    private val cjkFunction = setOf('的', '了', '是', '在', '吗', '呢', '吧', '和', '与', '就', '也', '都', '有', '不', '这', '那', '我', '你', '他', '她', '它', '什', '么')

    private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code) in setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    )

    fun tokens(text: String): Set<String> {
        val out = mutableSetOf<String>()
        val word = StringBuilder()
        val run = StringBuilder()
        fun flushWord() {
            val w = word.toString().lowercase()
            if (w.length >= 2 && w !in stopWords) out += w
            word.clear()
        }
        fun flushRun() {
            if (run.length == 1 && run[0] !in cjkFunction) out += run.toString()
            for (i in 0 until run.length - 1) {
                if (run[i] !in cjkFunction && run[i + 1] !in cjkFunction) out += run.substring(i, i + 2)
            }
            run.clear()
        }
        for (c in text) {
            when {
                isCjk(c) -> { flushWord(); run.append(c) }
                c.isLetterOrDigit() -> { flushRun(); word.append(c) }
                else -> { flushWord(); flushRun() }
            }
        }
        flushWord(); flushRun()
        return out
    }

    data class Hit(val chunk: SourceChunk, val score: Int)

    /** Top [limit] chunks with at least one shared token, best first (ties keep document order). */
    fun top(question: String, chunks: List<SourceChunk>, limit: Int = 4): List<Hit> {
        val q = tokens(question)
        if (q.isEmpty()) return emptyList()
        return chunks.map { Hit(it, (tokens(it.text) intersect q).size) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(limit)
    }
}

/** Sentence splitting shared by the ask fallback and the offline fake model. Sentences are exact substrings of the text. */
object Sentences {
    private val enders = setOf('。', '！', '？', '!', '?', '；')

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val boundary = when {
                c in enders -> true
                c == '.' -> i + 1 >= text.length || text[i + 1].isWhitespace()
                c == '\n' && i + 1 < text.length && text[i + 1] == '\n' -> true
                else -> false
            }
            if (boundary) {
                val s = text.substring(start, i + 1).trim()
                if (s.isNotBlank()) out += s
                start = i + 1
            }
            i++
        }
        val tail = text.substring(start).trim()
        if (tail.isNotBlank()) out += tail
        return out
    }
}
