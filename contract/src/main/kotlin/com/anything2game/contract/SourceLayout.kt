package com.anything2game.contract

import kotlinx.serialization.Serializable

// Where a PDF document's text sits on its pages, so "Show source" can mark a quote on the real page.
// Keyed by chunk, not by citation: every ref made later (tutor turns, Ask, compile, scripts) gets rectangles for free,
// and no ref creation or validation site changes. Additive and optional everywhere it travels (DocumentResponse.layout).

/**
 * Text geometry of one uploaded PDF, computed by the server with PDFBox while it still has the bytes (it keeps none).
 * Only PDF uploads that ask for it (`?layout=1`) get one; chunks the server could not align are simply absent.
 */
@Serializable
data class DocumentLayout(val pages: List<PageBox> = emptyList(), val chunks: List<ChunkLayout> = emptyList())

/** Crop-box size in points (what a renderer draws), so a client can tell a rendered page of another file by its shape. */
@Serializable
data class PageBox(val pageIndex: Int, val width: Float, val height: Float)

@Serializable
data class ChunkLayout(val chunkId: String, val runs: List<GlyphRun> = emptyList())

/**
 * Consecutive non-whitespace characters of one chunk on one printed line. [first] counts the chunk's non-whitespace
 * characters ([SourceLayout.isGap]); [top]/[bottom] bound the line and [x] holds each character's left and right edge,
 * all in [SourceLayout.SCALE] units from the page's top-left. [x] is delta-coded (each value minus the previous one,
 * the first one absolute) because integers that small keep a 200-page upload's JSON well under a megabyte or two.
 */
@Serializable
data class GlyphRun(val first: Int, val top: Int, val bottom: Int, val x: List<Int>)

/** A highlight rectangle, 0..1 of the page's width/height from the top-left. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

object SourceLayout {
    const val SCALE = 10_000

    /** The separator predicate on both sides (server glyphs, phone chunk text); wider than `\s`, so NBSP never shifts the count. */
    fun isGap(c: Char): Boolean = c.isWhitespace()

    /** A quote matches the chunk with whitespace normalised (the citation rule), so any run of spaces may sit between its words. */
    fun find(text: String, quote: String): IntRange? {
        val words = quote.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return Regex(words.joinToString("\\s+") { Regex.escape(it) }).find(text)?.range
    }

    fun encode(edges: List<Int>): List<Int> = edges.mapIndexed { i, v -> if (i == 0) v else v - edges[i - 1] }
    fun decode(x: List<Int>): IntArray { var at = 0; return IntArray(x.size) { i -> (at + x[i]).also { at = it } } }

    /**
     * The rectangles that mark [quote] in [chunk], one per printed line (a quote wrapping three lines gives three).
     * Empty when the quote is not in the chunk or the chunk has no usable geometry; callers then show the text instead.
     * A quote never crosses a page because a chunk never does.
     */
    fun rects(chunk: SourceChunk, layout: ChunkLayout?, quote: String): List<NormRect> {
        if (layout == null || layout.chunkId != chunk.chunkId) return emptyList()
        val range = find(chunk.text, quote) ?: return emptyList()
        val a = (0 until range.first).count { !isGap(chunk.text[it]) }
        val b = a + range.count { !isGap(chunk.text[it]) } - 1
        val total = chunk.text.count { !isGap(it) }
        if (b < a) return emptyList()
        val out = mutableListOf<NormRect>()
        for (run in layout.runs) {
            val n = run.x.size / 2
            if (run.x.size % 2 != 0 || n == 0 || run.first < 0 || run.first + n > total || run.top !in 0..SCALE || run.bottom !in run.top..SCALE) continue
            val lo = maxOf(a, run.first); val hi = minOf(b, run.first + n - 1)
            if (lo > hi) continue
            val e = decode(run.x)
            val left = e[(lo - run.first) * 2]; val right = e[(hi - run.first) * 2 + 1]
            if (left !in 0..SCALE || right !in left..SCALE) continue
            val r = NormRect(left / SCALE.toFloat(), run.top / SCALE.toFloat(), right / SCALE.toFloat(), run.bottom / SCALE.toFloat())
            val last = out.lastOrNull()
            // Two runs of one line (the server breaks a run where x jumps back, e.g. kerning) become one mark.
            if (last != null && sameLine(last, r)) out[out.lastIndex] = NormRect(minOf(last.left, r.left), minOf(last.top, r.top), maxOf(last.right, r.right), maxOf(last.bottom, r.bottom))
            else out += r
        }
        return out
    }

    private fun sameLine(p: NormRect, q: NormRect): Boolean {
        val overlap = minOf(p.bottom, q.bottom) - maxOf(p.top, q.top)
        return overlap > 0.6f * minOf(p.bottom - p.top, q.bottom - q.top) && q.left >= p.left - 0.002f
    }
}
