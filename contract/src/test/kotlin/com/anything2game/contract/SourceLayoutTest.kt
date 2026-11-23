package com.anything2game.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLayoutTest {
    /** One run per line: [chars] glyphs of equal [w] width starting at [left], the run's [first] non-whitespace index. */
    private fun run(first: Int, chars: Int, left: Int, w: Int, top: Int, bottom: Int) =
        GlyphRun(first, top, bottom, SourceLayout.encode((0 until chars).flatMap { listOf(left + it * w, left + it * w + w - 5) }))

    @Test fun `edges survive the delta coding`() {
        val edges = listOf(1200, 1290, 1300, 1390, 1385, 1480)
        assertEquals(edges, SourceLayout.decode(SourceLayout.encode(edges)).toList())
    }

    @Test fun `CJK text without spaces is cut at the quoted characters, and wrapping gives a rect per line`() {
        val chunk = SourceChunk("c1", 0, "1", "间隔重复能让记忆保持更久，提取练习也是。")
        // Line 1 holds the first 10 characters, line 2 the remaining 10.
        val layout = ChunkLayout("c1", listOf(run(0, 10, 1000, 300, 1000, 1300), run(10, 10, 1000, 300, 1400, 1700)))
        val one = SourceLayout.rects(chunk, layout, "记忆保持")
        assertEquals(listOf(NormRect(0.28f, 0.1f, 0.3995f, 0.13f)), one)
        val two = SourceLayout.rects(chunk, layout, "保持更久，提取练习")
        assertEquals(2, two.size)
        assertEquals(0.34f, two[0].left, 1e-4f); assertEquals(0.3995f, two[0].right, 1e-4f)
        assertEquals(0.1f, two[1].left, 1e-4f); assertEquals(0.3095f, two[1].right, 1e-4f) // "更" opens line 2, "习" is its 7th glyph
    }

    @Test fun `whitespace in the chunk, NBSP included, does not shift the character count`() {
        val chunk = SourceChunk("c1", 0, null, "ab\u00A0cd  ef\ngh")
        // Non-whitespace characters: a b c d e f g h (8), all on one line.
        val layout = ChunkLayout("c1", listOf(run(0, 8, 0, 100, 0, 200)))
        val r = SourceLayout.rects(chunk, layout, "ef gh")
        assertEquals(1, r.size)
        assertEquals(0.04f, r[0].left, 1e-4f); assertEquals(0.0795f, r[0].right, 1e-4f)
    }

    @Test fun `an absent quote, a foreign layout or broken geometry give no rectangles`() {
        val chunk = SourceChunk("c1", 0, null, "alpha beta gamma")
        val good = ChunkLayout("c1", listOf(run(0, 14, 0, 100, 0, 200)))
        assertTrue(SourceLayout.rects(chunk, good, "delta").isEmpty())
        assertTrue(SourceLayout.rects(chunk, good.copy(chunkId = "c2"), "beta").isEmpty())
        assertTrue(SourceLayout.rects(chunk, null, "beta").isEmpty())
        assertTrue(SourceLayout.rects(chunk, ChunkLayout("c1", listOf(run(10, 14, 0, 100, 0, 200))), "beta").isEmpty()) // runs past the chunk
        assertTrue(SourceLayout.rects(chunk, ChunkLayout("c1", listOf(GlyphRun(0, 0, 200, listOf(1, 2, 3)))), "alpha").isEmpty())
    }

    @Test fun `a document response without layout (older server or cache) decodes, and one with layout round-trips`() {
        val old = ContractJson.json.decodeFromString(DocumentResponse.serializer(), """{"documentId":"d","documentVersion":"v","pageCount":1,"title":"T"}""")
        assertEquals(null, old.layout)
        val full = old.copy(layout = DocumentLayout(listOf(PageBox(0, 612f, 792f)), listOf(ChunkLayout("c1", listOf(run(0, 2, 0, 100, 0, 200))))))
        assertEquals(full, ContractJson.json.decodeFromString(DocumentResponse.serializer(), ContractJson.json.encodeToString(DocumentResponse.serializer(), full)))
    }
}
