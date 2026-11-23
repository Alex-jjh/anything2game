package com.anything2game.server

import com.anything2game.contract.ContractJson
import com.anything2game.contract.PresetSourceLayout
import com.anything2game.contract.SourceLayout
import com.anything2game.server.ingest.Chunker
import com.anything2game.server.ingest.PdfTextExtractor
import com.anything2game.server.ingest.PresetLayoutTool
import org.apache.pdfbox.Loader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** The offline layout tool measures a preset's own chunks on a real (generated) PDF, and refuses anything it cannot vouch for. */
class PresetLayoutToolTest {
    private val pages = listOf(
        listOf("Retrieval practice means pulling an idea out of memory", "instead of reading it again. Spaced sessions make the",
            "effect last for weeks, as studies of students show. A quiz", "after a lecture beats a second reading of the same notes,",
            "even when the quiz is short and nobody grades it at all."),
        listOf("Interleaving mixes different problem types in one session,", "which feels harder but transfers better to new problems.",
            "Students who interleave choose the method, not only apply it,", "and that choice is what the final exam tends to ask for.",
            "Blocked practice hides that step, so it looks easier than it is."),
        listOf("Elaboration asks why and how a fact is true, linking it", "to what the learner already knows. Concrete examples help",
            "most when two of them differ on the surface and share the", "same idea underneath, so the idea is what gets remembered,",
            "and the surface details are what the learner learns to ignore."),
    )
    private val pdf = makePdfLines(pages)

    /** A preset package made from this PDF the way the upload path makes one: its sources are the chunker's output. */
    private fun presetOf(bytes: ByteArray) = PdfTextExtractor(maxPages = 10).extract(bytes).let { read ->
        Examples.retrieval.copy(packageId = "demo_tool", documentId = "doc_demo_tool", documentVersion = Chunker.documentVersion(read), sources = Chunker.chunk(read))
    }

    @Test fun `the layout aligns every chunk with its real text and records what it was measured on`() {
        val pkg = presetOf(pdf)
        assertEquals(3, pkg.sources.size)
        val report = PresetLayoutTool.measure(pkg, pdf, pdfSource = "https://example.org/paper.pdf")
        val l = report.layout
        assertEquals(listOf("c1", "c2", "c3"), l.layout.chunks.map { it.chunkId })
        assertEquals(MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) }, l.pdfSha256)
        assertEquals(pdf.size.toLong(), l.pdfBytes)
        assertEquals(3, l.pageCount)
        assertEquals(Triple("demo_tool", "doc_demo_tool", pkg.documentVersion), Triple(l.packageId, l.documentId, l.documentVersion))
        assertEquals("https://example.org/paper.pdf", l.pdfSource)
        assertTrue(l.unaligned.isEmpty())
        report.coverage.values.forEach { assertEquals(1.0, it, 1e-9) }
        // Each chunk as the phone will use it: its whole text gives one mark per printed line, and a quote lands on its line.
        val byId = l.layout.chunks.associateBy { it.chunkId }
        pkg.sources.forEach { c -> assertEquals(c.chunkId, 5, SourceLayout.rects(c, byId[c.chunkId], c.text).size) }
        val r = SourceLayout.rects(pkg.sources[1], byId["c2"], "transfers better to new problems")
        assertEquals(1, r.size)
        assertEquals((LINES_TOP - LINES_LEADING).let { (792f - it) / 792f }, (r[0].top + r[0].bottom) / 2, 0.012f)
        // What it writes is what the pack reads back.
        val decoded = ContractJson.json.decodeFromString(PresetSourceLayout.serializer(), PresetLayoutTool.encode(l))
        assertEquals(l, decoded.validate(pkg))
        assertEquals(File("samples/demo/layout.demo.tool.json").absoluteFile, PresetLayoutTool.defaultOut(File("samples/demo/package.demo.tool.json")))
    }

    @Test fun `a PDF of other text, or a file that is not a PDF, is refused before anything is measured`() {
        val pkg = presetOf(pdf)
        val other = makePdfLines(pages.map { p -> p.map { it.replace("idea", "notion") } })
        assertTrue(assertThrows(PresetLayoutTool.Refused::class.java) { PresetLayoutTool.measure(pkg, other) }.message!!.contains("not the document"))
        assertThrows(PresetLayoutTool.Refused::class.java) { PresetLayoutTool.measure(pkg, "plain text".toByteArray()) }
        // Same text, sources edited by hand: the chunkIds could not be trusted, so no layout.
        val edited = pkg.copy(sources = pkg.sources.map { if (it.chunkId == "c2") it.copy(pageLabel = "ii") else it })
        assertTrue(assertThrows(PresetLayoutTool.Refused::class.java) { PresetLayoutTool.measure(edited, pdf) }.message!!.contains("first difference at c2"))
    }

    @Test fun `a chunk that cannot be aligned fails loudly, unless it is accepted by name`() {
        // Page 2 rotated (the preset made from this very file): PDFBox gives no trusted coordinates there, so c2 cannot be placed.
        val rotated = Loader.loadPDF(pdf).use { doc -> doc.getPage(1).rotation = 90; ByteArrayOutputStream().also { doc.save(it) }.toByteArray() }
        val pkg = presetOf(rotated)
        val e = assertThrows(PresetLayoutTool.Refused::class.java) { PresetLayoutTool.measure(pkg, rotated) }
        assertTrue(e.message, e.message!!.contains("c2 0.00") && !e.message!!.contains("c1 ") && !e.message!!.contains("c3 "))
        val accepted = PresetLayoutTool.measure(pkg, rotated, allowUnaligned = setOf("c2")).layout
        assertEquals(listOf("c2"), accepted.unaligned)
        assertEquals(listOf("c1", "c3"), accepted.layout.chunks.map { it.chunkId })
        assertEquals("the real page count, not the pages with geometry", 3, accepted.pageCount)
        assertThrows(PresetLayoutTool.Refused::class.java) { PresetLayoutTool.measure(pkg, rotated, allowUnaligned = setOf("c99")) }
    }
}
