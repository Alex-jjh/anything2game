package com.anything2game.server

import com.anything2game.contract.ContractJson
import com.anything2game.contract.DocumentResponse
import com.anything2game.contract.NormRect
import com.anything2game.contract.SourceLayout
import com.anything2game.server.ingest.ChunkLayouts
import com.anything2game.server.ingest.Chunker
import com.anything2game.server.ingest.PdfTextExtractor
import io.ktor.http.HttpStatusCode
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.util.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Highlight rectangles computed from PDFBox glyph positions land on the quoted words of a real (generated) PDF. */
class SourceLayoutTest {
    private val lines = listOf(
        "Retrieval practice means pulling an idea out of memory",
        "instead of reading it again. Spaced sessions make the",
        "effect last for weeks, as studies of students show.",
    )
    private val page2 = listOf("Interleaving mixes different problem types in one session,", "which feels harder but transfers better to new problems.")
    private val pdf = makePdfLines(listOf(lines, page2))
    private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val pageW = 612f
    private val pageH = 792f
    private fun width(s: String) = font.getStringWidth(s) / 1000f * LINES_FONT_SIZE
    /** Baseline of line [i], in points from the page's top. */
    private fun baseline(i: Int) = pageH - (LINES_TOP - i * LINES_LEADING)
    private val tol = 1.5f / pageW

    private fun layoutOf(bytes: ByteArray) = PdfTextExtractor(maxPages = 10).extractWithGlyphs(bytes).let { read ->
        val chunks = Chunker.chunk(read.pages)
        chunks to ChunkLayouts.build(chunks, read.glyphs)
    }

    private fun assertOnLine(r: NormRect, line: Int) {
        val base = baseline(line) / pageH
        assertTrue("top ${r.top} above line $line", r.top < base && r.top > (baseline(line) - LINES_LEADING) / pageH)
        assertTrue("bottom ${r.bottom} below line $line", r.bottom > base && r.bottom < (baseline(line) + LINES_LEADING / 2) / pageH)
    }

    @Test fun `a quote that wraps two lines gets one rectangle per line over exactly the quoted words`() {
        val (chunks, layout) = layoutOf(pdf)
        assertEquals(listOf(0, 1), chunks.map { it.pageIndex })
        val c1 = chunks[0]
        val quote = "pulling an idea out of memory instead of reading it again"
        val rects = SourceLayout.rects(c1, layout.chunks.first { it.chunkId == c1.chunkId }, quote)
        assertEquals(2, rects.size)
        val (first, second) = rects
        assertOnLine(first, 0); assertOnLine(second, 1)
        assertEquals((LINES_LEFT + width("Retrieval practice means ")) / pageW, first.left, tol)
        assertEquals((LINES_LEFT + width(lines[0])) / pageW, first.right, tol)
        assertEquals(LINES_LEFT / pageW, second.left, tol)
        assertEquals((LINES_LEFT + width("instead of reading it again")) / pageW, second.right, tol)
        assertEquals(listOf(612f to 792f, 612f to 792f), layout.pages.map { it.width to it.height })
    }

    @Test fun `a quote on the second page is laid out on that page, and three lines give three rectangles`() {
        val (chunks, layout) = layoutOf(pdf)
        val c2 = chunks[1]
        val r = SourceLayout.rects(c2, layout.chunks.first { it.chunkId == c2.chunkId }, "transfers better")
        assertEquals(1, r.size); assertOnLine(r[0], 1)
        assertEquals((LINES_LEFT + width("which feels harder but ")) / pageW, r[0].left, tol)
        val c1 = chunks[0]
        val all = SourceLayout.rects(c1, layout.chunks.first { it.chunkId == c1.chunkId }, "memory instead of reading it again. Spaced sessions make the effect")
        assertEquals(3, all.size)
        all.forEachIndexed { i, rect -> assertOnLine(rect, i) }
    }

    @Test fun `a quote that is not in the chunk, or a chunk without layout, gives no rectangles`() {
        val (chunks, layout) = layoutOf(pdf)
        assertTrue(SourceLayout.rects(chunks[0], layout.chunks[0], "not in this document").isEmpty())
        assertTrue(SourceLayout.rects(chunks[0], null, "pulling an idea").isEmpty())
        assertTrue(SourceLayout.rects(chunks[0], layout.chunks[1], "pulling an idea").isEmpty()) // another chunk's geometry
    }

    @Test fun `a rotated page gets no layout rather than boxes in the wrong place`() {
        val rotated = Loader.loadPDF(pdf).use { doc -> doc.getPage(0).rotation = 90; ByteArrayOutputStream().also { doc.save(it) }.toByteArray() }
        val (chunks, layout) = layoutOf(rotated)
        assertEquals(listOf(chunks[1].chunkId), layout.chunks.map { it.chunkId })
        assertEquals(listOf(1), layout.pages.map { it.pageIndex })
    }

    /** One page drawn by [draw]; producers often scale text through the CTM (`cm`) rather than the font size. */
    private fun onePage(draw: PDPageContentStream.() -> Unit): ByteArray = PDDocument().use { doc ->
        val page = PDPage(); doc.addPage(page)
        PDPageContentStream(doc, page).use { it.draw() }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** [lines] at the usual baselines, in a user space scaled by [ctm] with the font size divided by it. */
    private fun scaledPdf(ctm: Float) = onePage {
        transform(Matrix.getScaleInstance(ctm, ctm))
        beginText(); setFont(font, LINES_FONT_SIZE / ctm); newLineAtOffset(LINES_LEFT / ctm, LINES_TOP / ctm)
        lines.forEachIndexed { i, line -> if (i > 0) newLineAtOffset(0f, -LINES_LEADING / ctm); showText(line) }
        endText()
    }

    @Test fun `text scaled through the CTM gets bands of its real size, whether the CTM shrinks or grows it`() {
        for (ctm in listOf(0.5f, 12f)) { // 24 Tf in a half-size space; 1 Tf in a 12x space: both 12 pt on the page
            val (chunks, layout) = layoutOf(scaledPdf(ctm))
            val rects = SourceLayout.rects(chunks[0], layout.chunks.first(), "pulling an idea out of memory instead of reading it again")
            assertEquals("ctm $ctm", 2, rects.size)
            assertOnLine(rects[0], 0); assertOnLine(rects[1], 1)
            rects.forEach { assertEquals("ctm $ctm band", LINES_FONT_SIZE * 1.15f / pageH, it.bottom - it.top, 0.5f / pageH) }
            assertEquals((LINES_LEFT + width("Retrieval practice means ")) / pageW, rects[0].left, tol)
        }
    }

    @Test fun `vertical text on an upright page gets no rectangles, while the upright text beside it still does`() {
        val vertical = "arXiv stamp running up the margin of the page"
        val bytes = onePage {
            beginText(); setFont(font, LINES_FONT_SIZE); newLineAtOffset(LINES_LEFT, LINES_TOP); showText(lines[0]); endText()
            beginText(); setFont(font, LINES_FONT_SIZE); setTextMatrix(Matrix(0f, 1f, -1f, 0f, 40f, 200f)); showText(vertical); endText()
        }
        val (chunks, layout) = layoutOf(bytes)
        val chunk = chunks.single()
        // PDFBox spaces vertical text oddly ("ar X iv s ta m p"), so quote it as extracted.
        val quoted = chunk.text.substringAfter("memory").trim().take(24)
        assertTrue(chunk.text, quoted.startsWith("ar"))
        val cl = layout.chunks.firstOrNull { it.chunkId == chunk.chunkId }
        assertTrue(SourceLayout.rects(chunk, cl, quoted).isEmpty())
        val ok = SourceLayout.rects(chunk, cl, "pulling an idea out of memory")
        assertEquals(1, ok.size); assertOnLine(ok[0], 0)
    }

    @Test fun `the API returns the layout only to a PDF upload that asks, and old responses still decode`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val asked = api.uploadPdf(token, pdf, path = "/v1/documents?layout=1")
            assertEquals(HttpStatusCode.Created, asked.status)
            val doc = api.decode(DocumentResponse.serializer(), asked)
            val layout = checkNotNull(doc.layout) { "asked for a layout" }
            val stored = env.store.getDocument(doc.documentId)!!
            assertEquals(stored.chunks.map { it.chunkId }, layout.chunks.map { it.chunkId })
            assertEquals(2, SourceLayout.rects(stored.chunks[0], layout.chunks[0], "pulling an idea out of memory instead of reading").size)

            assertNull(api.decode(DocumentResponse.serializer(), api.uploadPdf(token, pdf)).layout)
            assertNull(api.decode(DocumentResponse.serializer(), api.uploadText(token, lines)).layout)
            val old = """{"documentId":"doc_1","documentVersion":"sha256:x","pageCount":2,"title":"Old"}"""
            assertEquals(DocumentResponse("doc_1", "sha256:x", 2, "Old"), ContractJson.json.decodeFromString(DocumentResponse.serializer(), old))
        }
    }
}
