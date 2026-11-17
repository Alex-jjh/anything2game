package com.anything2game.server.ingest

import com.anything2game.contract.PackageValidator
import com.anything2game.contract.PageText
import com.anything2game.contract.SourceChunk
import java.security.MessageDigest

/**
 * Splits pages into citation-sized chunks.
 * Each page is split into paragraphs; paragraphs are merged into chunks of about [MIN]..[MAX] characters and a chunk
 * never crosses a page. Ids are `c1..cn` in reading order, `pageIndex` is the 0-based page, `pageLabel` the printed number.
 */
object Chunker {
    const val MIN = 300
    const val MAX = 1200

    private val paragraphBreak = Regex("\\n\\s*\\n")
    private val sentenceEnd = Regex("(?<=[.!?。！？])(?=\\s|[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])")

    fun chunk(pages: List<PageText>): List<SourceChunk> {
        val out = mutableListOf<SourceChunk>()
        for (page in pages.sortedBy { it.pageIndex }) {
            val pieces = page.text.split(paragraphBreak)
                .map { PackageValidator.normalise(it) }
                .filter { it.isNotEmpty() }
                .flatMap { splitLong(it) }
            val label = page.pageLabel ?: (page.pageIndex + 1).toString()
            for (text in merge(pieces)) {
                out += SourceChunk(chunkId = "c${out.size + 1}", pageIndex = page.pageIndex, pageLabel = label, text = text)
            }
        }
        return out
    }

    /**
     * A paragraph longer than [MAX] is cut at sentence ends, and at spaces (or anywhere) if a single sentence is still too long.
     * Pieces keep their original whitespace and are concatenated, so no character of the text is changed or invented
     * (Chinese text gets no spaces inserted).
     */
    private fun splitLong(paragraph: String): List<String> {
        if (paragraph.length <= MAX) return listOf(paragraph)
        val parts = mutableListOf<String>()
        var cur = StringBuilder()
        for (sentence in paragraph.split(sentenceEnd).filter { it.isNotBlank() }) {
            for (piece in hardSplit(sentence)) {
                if (cur.isNotEmpty() && cur.length + piece.length > MAX) {
                    parts += cur.toString().trim(); cur = StringBuilder()
                }
                cur.append(if (cur.isEmpty()) piece.trimStart() else piece)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString().trim()
        return parts
    }

    private fun hardSplit(sentence: String): List<String> {
        if (sentence.length <= MAX) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX) {
            val space = rest.lastIndexOf(' ', MAX)
            val cut = if (space > MAX / 2) space else MAX
            out += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Small pieces are merged until they reach [MIN]; a short tail is folded into the previous chunk when it fits. */
    private fun merge(pieces: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        var cur = ""
        for (p in pieces) {
            cur = when {
                cur.isEmpty() -> p
                cur.length < MIN && cur.length + 2 + p.length <= MAX -> "$cur\n\n$p"
                else -> { chunks += cur; p }
            }
        }
        if (cur.isNotEmpty()) {
            if (cur.length < MIN && chunks.isNotEmpty() && chunks.last().length + 2 + cur.length <= MAX) {
                chunks[chunks.lastIndex] = chunks.last() + "\n\n" + cur
            } else chunks += cur
        }
        return chunks
    }

    /** `sha256:` + hex of the whitespace-normalised full text (pages joined by a newline). Same text, same version. */
    fun documentVersion(pages: List<PageText>): String {
        val full = pages.sortedBy { it.pageIndex }.joinToString("\n") { PackageValidator.normalise(it.text) }
        val digest = MessageDigest.getInstance("SHA-256").digest(full.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
