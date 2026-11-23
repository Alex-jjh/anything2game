package com.anything2game.server.ingest

import com.anything2game.contract.ChunkLayout
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.GlyphRun
import com.anything2game.contract.PageBox
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lays each chunk's characters onto the page glyphs PDFBox reported. The Chunker only changes whitespace, so a chunk's
 * non-whitespace characters are the next slice of its page's [PageGlyphs.chars]; the slice is looked up (not assumed),
 * and a chunk whose characters cannot be found gets no layout instead of boxes in the wrong place.
 */
object ChunkLayouts {
    fun build(chunks: List<SourceChunk>, pages: List<PageGlyphs>): DocumentLayout {
        val byPage = pages.associateBy { it.pageIndex }
        val cursor = mutableMapOf<Int, Int>()
        val out = mutableListOf<ChunkLayout>()
        for (chunk in chunks) {
            val page = byPage[chunk.pageIndex]?.takeIf { it.chars.isNotEmpty() } ?: continue
            val want = chunk.text.filter { !SourceLayout.isGap(it) }
            if (want.isEmpty()) continue
            val at = page.chars.indexOf(want, cursor[page.pageIndex] ?: 0)
            if (at < 0) continue
            cursor[page.pageIndex] = at + want.length
            runs(page, at, want.length).takeIf { it.isNotEmpty() }?.let { out += ChunkLayout(chunk.chunkId, it) }
        }
        val boxes = pages.filter { it.chars.isNotEmpty() }.map { PageBox(it.pageIndex, it.width, it.height) }
        return DocumentLayout(boxes, out)
    }

    /** Glyphs [from, from+count) split into runs of one line each; a run breaks where the baseline moves or x jumps back. */
    private fun runs(page: PageGlyphs, from: Int, count: Int): List<GlyphRun> {
        val runs = mutableListOf<GlyphRun>()
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            val boxes = (start until end).map { page.boxes[it]!! }
            val edges = boxes.flatMap { listOf(qx(page, minOf(it.left, it.right)), qx(page, maxOf(it.left, it.right))) }
            runs += GlyphRun(start - from, qy(page, boxes.minOf { it.top }), qy(page, boxes.maxOf { it.bottom }), SourceLayout.encode(edges))
            start = -1
        }
        for (i in from until from + count) {
            val box = page.boxes.getOrNull(i)
            if (box == null) { close(i); continue }
            if (start >= 0) {
                val prev = page.boxes[i - 1]!!
                val h = minOf(prev.bottom - prev.top, box.bottom - box.top).coerceAtLeast(0.1f)
                if (abs(prev.bottom - box.bottom) > h * 0.5f || box.left < prev.left - h * 0.5f) close(i)
            }
            if (start < 0) start = i
        }
        close(from + count)
        return runs
    }

    private fun qx(page: PageGlyphs, v: Float) = (v / page.width * SourceLayout.SCALE).roundToInt().coerceIn(0, SourceLayout.SCALE)
    private fun qy(page: PageGlyphs, v: Float) = (v / page.height * SourceLayout.SCALE).roundToInt().coerceIn(0, SourceLayout.SCALE)
}
