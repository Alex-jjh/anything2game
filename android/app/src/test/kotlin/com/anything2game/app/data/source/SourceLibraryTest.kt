package com.anything2game.app.data.source

import com.anything2game.app.awaitIo
import com.anything2game.app.data.tutor.AttachResult
import com.anything2game.app.data.tutor.PresetSources
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.app.data.tutor.SourceDocumentStore
import com.anything2game.app.data.tutor.SourceLayouts
import com.anything2game.contract.ChunkLayout
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.GlyphRun
import com.anything2game.contract.PageBox
import com.anything2game.contract.PresetSourceLayout
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** The one place a source's original is looked up: ordered resolvers, first one that has the file wins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val chunks = listOf(SourceChunk("c1", 0, "1", "Spaced practice beats cramming because forgetting between sessions makes each retrieval harder."))
    private val text = SourceText("Lecture", chunks)
    private val key = SourceKey("doc", "v1", CourseRef("course", "1"))
    private fun pdf(name: String) = Original.Pdf(SourceDocument("doc", "v1", 1, File(tmp.root, "$name.pdf"), DocumentLayout()))
    private fun asked(log: MutableList<String>, name: String, answer: Original?) = OriginalResolver { log += name; answer }

    @Test fun `the first resolver that has the original wins, later ones are not asked (earlier ones are re-checked once)`() {
        val log = mutableListOf<String>()
        val second = pdf("second")
        val library = ResolvingSourceLibrary(listOf(asked(log, "none", null), asked(log, "second", second), asked(log, "third", pdf("third"))))
        val file = awaitIo { library.open(key, text) }
        assertSame(second, file.original)
        assertEquals(listOf("none", "second", "none"), log)
        assertEquals("the text always comes along", "Lecture" to chunks, file.title to file.chunks)
    }

    @Test fun `with no original anywhere the file has none, and the attach offer is asked for`() {
        val log = mutableListOf<String>()
        val offer = AttachOffer(com.anything2game.app.data.tutor.PresetPdf("course", "doc", "v1", 10, null)) { AttachResult.NotPdf }
        val library = ResolvingSourceLibrary(listOf(asked(log, "a", null)), AttachOffers { log += "offer"; offer })
        val file = awaitIo { library.open(key, text) }
        assertNull(file.original)
        assertSame(offer, file.attach)
        assertEquals(listOf("a", "offer"), log)
    }

    @Test fun `an original found means no attach offer is even looked for`() {
        val log = mutableListOf<String>()
        val library = ResolvingSourceLibrary(listOf(asked(log, "a", pdf("a"))), AttachOffers { log += "offer"; null })
        assertNull(awaitIo { library.open(key, text) }.attach)
        assertEquals(listOf("a"), log)
    }

    @Test fun `an earlier original that appears while a later one is being opened wins`() {
        val upload = pdf("upload")
        var appeared = false
        val first = OriginalResolver { if (appeared) upload else null }
        val slow = OriginalResolver { appeared = true; pdf("bundled") }
        assertSame(upload, awaitIo { ResolvingSourceLibrary(listOf(first, slow)).open(key, text) }.original)
    }

    @Test fun `a resolver that fails counts as not here`() {
        val kept = pdf("kept")
        val library = ResolvingSourceLibrary(listOf(OriginalResolver { error("damaged record") }, OriginalResolver { kept }))
        assertSame(kept, awaitIo { library.open(key, text) }.original)
        val broken = ResolvingSourceLibrary(listOf(OriginalResolver { error("damaged record") }), AttachOffers { error("no") })
        assertNull(awaitIo { broken.open(key, text) }.original)
    }

    @Test fun `adding a resolver in front changes which original is found and nothing else`() {
        val base = listOf(asked(mutableListOf(), "kept", pdf("kept")))
        val before = awaitIo { ResolvingSourceLibrary(base).open(key, text) }
        val slides = pdf("fake-slides")
        val after = awaitIo { ResolvingSourceLibrary(listOf(OriginalResolver { slides }) + base).open(key, text) }
        assertSame(slides, after.original)
        assertEquals(before.title to before.chunks, after.title to after.chunks)
    }

    // --- the phone's real chain over real stores ---
    private val bytes = "%PDF-1.7\n% the exact file the layout was measured on\n".toByteArray()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun layout(): PresetSourceLayout {
        val n = chunks[0].text.count { !SourceLayout.isGap(it) }
        val run = GlyphRun(0, 1000, 1250, SourceLayout.encode((0 until n).flatMap { listOf(500 + it * 90, 500 + it * 90 + 80) }))
        return PresetSourceLayout(packageId = "course", documentId = "doc", documentVersion = "v1", pdfSha256 = sha(bytes), pdfBytes = bytes.size.toLong(), pageCount = 1, pdfSource = "https://example.org/x.pdf", layout = DocumentLayout(listOf(PageBox(0, 612f, 792f)), listOf(ChunkLayout("c1", listOf(run)))))
    }
    private fun presets(dir: String, bundled: Boolean) =
        PresetSources(SourceLayouts { layout() }, SourceDocumentStore(File(tmp.root, dir))) { if (bundled) bytes.inputStream() else null }
    private fun phone(presets: PresetSources, imported: SourceDocument? = null) = ResolvingSourceLibrary(
        listOf(ImportedPdfResolver { _, _ -> imported }, AttachedPdfResolver(presets), BundledPdfResolver(presets)), PresetAttachOffers(presets))

    @Test fun `nothing on the phone gives the text and an offer to attach`() {
        val file = awaitIo { phone(presets("none", bundled = false)).open(key, text) }
        assertNull(file.original)
        assertEquals("course", file.attach!!.expected.packageId)
    }

    @Test fun `the bundled sample paper is found with no attaching, and no offer`() {
        val file = awaitIo { phone(presets("bundled", bundled = true)).open(key, text) }
        assertEquals(sha(bytes), (file.original as Original.Pdf).document.pdfSha256)
        assertNull(file.attach)
    }

    @Test fun `a PDF the reader attached is found without any bundle`() {
        val p = presets("attached", bundled = false)
        assertTrue(awaitIo { p.attach("course") { bytes.inputStream() } } is AttachResult.Accepted)
        val file = awaitIo { phone(p).open(key, text) }
        assertEquals(sha(bytes), (file.original as Original.Pdf).document.pdfSha256)
        assertNull(file.attach)
    }

    @Test fun `the reader's own import comes first, unless the source is scoped to the course's own paper`() {
        val p = presets("import", bundled = true)
        val imported = SourceDocument("doc", "v1", 4, File(tmp.root, "imported.pdf"), DocumentLayout())
        val library = phone(p, imported)
        assertSame(imported, (awaitIo { library.open(key, text) }.original as Original.Pdf).document)
        val scoped = key.copy(courses = listOf(CourseRef("course", "1", ownPaperOnly = true)))
        assertEquals("the bundled paper, never the import with the same labels", sha(bytes), (awaitIo { library.open(scoped, text) }.original as Original.Pdf).document.pdfSha256)
    }

    @Test fun `another edition of the document, or a source with no course, never gets the course's paper`() {
        val library = phone(presets("other", bundled = true))
        val otherEdition = awaitIo { library.open(key.copy(documentVersion = "v2"), text) }
        assertNull(otherEdition.original); assertNull(otherEdition.attach)
        assertNull(awaitIo { library.open(key.copy(courses = emptyList()), text) }.original)
    }
}
