package com.anything2game.app.ui.source

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.anything2game.app.A2gApplication
import com.anything2game.app.AppDependencies
import com.anything2game.app.awaitIo
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.data.source.*
import com.anything2game.app.data.tutor.AttachResult
import com.anything2game.app.data.tutor.PresetSources
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.app.data.tutor.SourceDocumentStore
import com.anything2game.app.data.tutor.SourceLayouts
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * A preset course's "Show source" with the reader's own PDF (ADR-019): the real preset packs and the app's own wiring,
 * a fake system file picker, and a fake renderer (Robolectric cannot render PDFs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetAttachTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication() as A2gApplication
    private val deps get() = app.dependencies

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    /** The system picker, answering with [file] as a document would come back (a one-off read, no lasting grant). */
    private class Picker(private val file: () -> File) : ActivityResultRegistryOwner {
        val asked = mutableListOf<Any?>()
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                asked += input
                dispatchResult(requestCode, Uri.fromFile(file()))
            }
        }
    }

    private class FakePages(override val pageCount: Int) : PageImages {
        override suspend fun render(index: Int, widthPx: Int): ImageBitmap = ImageBitmap(306, 396)
        override fun close() {}
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The viewer over the preset chain the app uses for a course's paper (attached, then bundled), fed by [presets]. */
    private fun library(presets: PresetSources): SourceLibrary =
        ResolvingSourceLibrary(listOf(AttachedPdfResolver(presets), BundledPdfResolver(presets)), PresetAttachOffers(presets))

    private fun show(presets: PresetSources, packageId: String, documentId: String, documentVersion: String, sources: List<SourceChunk>, refs: List<SourceRef>, picker: Picker, pages: Int = 3) = rule.setContent {
        A2gTheme {
            CompositionLocalProvider(LocalPageImages provides { FakePages(pages) }, LocalActivityResultRegistryOwner provides picker) {
                OpenSourceViewer(library(presets), SourceKey(documentId, documentVersion, CourseRef(packageId)), SourceText("The paper", sources), refs, onDismiss = {})
            }
        }
    }

    private fun contentPresets(dir: File = File(tmp.root, "source-documents")) = PresetSources(deps.content, SourceDocumentStore(dir))

    private fun attention() = awaitIo { deps.packages.load("demo_attention").getOrThrow() }
    private fun quoteOf(c: SourceChunk) = c.text.split(Regex("\\s+")).take(6).joinToString(" ")

    @Test fun `a preset course with nothing attached shows its text, with a quiet offer only where a layout ships`() {
        val presets = contentPresets()
        val expected = awaitIo { presets.available("demo_attention") }
        assertNotNull("the attention preset ships page geometry", expected)
        assertEquals("https://arxiv.org/pdf/1706.03762v7", expected!!.pdfSource)
        assertNull("the Chinese preset is a text course: nothing to attach", awaitIo { presets.available("demo_zh_spaced") })
        val pkg = attention()
        assertEquals(pkg.documentId to pkg.documentVersion, expected.documentId to expected.documentVersion)
        assertNull("nothing attached yet", awaitIo { presets.load("demo_attention", pkg.documentId, pkg.documentVersion) })

        val c2 = pkg.sources.first { it.chunkId == "c2" }
        show(presets, "demo_attention", pkg.documentId, pkg.documentVersion, pkg.sources, listOf(SourceRef("c2", quoteOf(c2))), Picker { error("not picked") })
        rule.waitUntil(5000) { has("source_highlighted") }
        assertTrue(!has("source_pdf_page") && !has("source_highlight_rect"))
        assertEquals("The original file isn't on this phone, so this is its text.", textOf("source_text_note"))
        assertEquals("Have the PDF? Attach it to see the real pages.", textOf("source_attach_status"))
        rule.onNodeWithTag("source_attach_pdf").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertHasClickAction()
            .assert(hasContentDescription("Attach your copy of the original PDF to see the real page"))
    }

    @Test fun `without a packed layout no attach offer appears`() {
        val pkg = attention()
        // The Chinese preset has no layout: nothing to attach to, however its text is opened.
        show(contentPresets(), "demo_zh_spaced", pkg.documentId, pkg.documentVersion, pkg.sources, listOf(SourceRef("c2", quoteOf(pkg.sources[1]))), Picker { error("not picked") })
        rule.waitUntil(5000) { has("source_highlighted") }
        assertTrue(!has("source_attach_pdf") && !has("source_attach_status"))
        assertTrue("still one plain sentence about the text", has("source_text_note"))
    }

    @Test fun `another file than the one measured is refused with a clear reason, and the text stays`() {
        val presets = contentPresets()
        val pkg = attention()
        // A real-looking PDF, maybe the same paper re-stamped: the bytes differ, so its geometry cannot be trusted.
        val other = File(tmp.root, "attention-mirror.pdf").apply { writeBytes("%PDF-1.5\n% another copy of the paper\n".toByteArray()) }
        val picker = Picker { other }
        show(presets, "demo_attention", pkg.documentId, pkg.documentVersion, pkg.sources, listOf(SourceRef("c2", quoteOf(pkg.sources[1]))), picker)
        rule.waitUntil(5000) { has("source_attach_pdf") }
        rule.onNodeWithTag("source_attach_pdf").performClick()
        rule.waitUntil(5000) { has("source_attach_refused") }
        assertEquals(listOf<Any?>(listOf("application/pdf")), picker.asked.map { (it as Array<*>).toList() })
        val message = textOf("source_attach_refused")
        assertTrue(message, message.contains("doesn't look like the same file") && message.contains("https://arxiv.org/pdf/1706.03762v7")
            && !Regex("(?i)fingerprint|measure").containsMatchIn(message))
        rule.onNodeWithTag("source_attach_download").assertHeightIsAtLeast(48.dp).assertHasClickAction()
        assertTrue("never drawn from another file's geometry", has("source_highlighted") && !has("source_pdf_page") && !has("source_highlight_rect"))
        rule.onNodeWithTag("source_attach_pdf").assertHeightIsAtLeast(48.dp) // "Choose another file"
        assertNull("nothing kept", awaitIo { presets.load("demo_attention", pkg.documentId, pkg.documentVersion) })
        // Nor does a non-PDF or a course without a layout change that.
        val notPdf = File(tmp.root, "notes.pdf").apply { writeText("just text") }
        assertEquals(AttachResult.NotPdf, awaitIo { presets.attach("demo_attention") { notPdf.inputStream() } })
        assertEquals(AttachResult.NoLayout, awaitIo { presets.attach("demo_zh_spaced") { notPdf.inputStream() } })
    }

    // --- the accepted path: a preset whose layout was measured on a file this test can hold ---
    private val sources = listOf(
        SourceChunk("c1", 0, "1", "Retrieval practice means pulling an idea out of memory instead of reading it again."),
        SourceChunk("c2", 1, "2", "Spaced practice beats cramming because forgetting between sessions makes each retrieval harder."),
    )
    private val quote = "forgetting between sessions makes each retrieval"
    private val bytes = "%PDF-1.7\n% the exact file the layout was measured on\n".toByteArray()

    private fun presetLayout(): PresetSourceLayout {
        fun run(c: SourceChunk, top: Int): ChunkLayout {
            val n = c.text.count { !SourceLayout.isGap(it) }
            return ChunkLayout(c.chunkId, listOf(GlyphRun(0, top, top + 250, SourceLayout.encode((0 until n).flatMap { listOf(500 + it * 90, 500 + it * 90 + 80) }))))
        }
        return PresetSourceLayout(packageId = "demo_tiny", documentId = "doc_demo_tiny", documentVersion = "sha256:tiny", pdfSha256 = sha(bytes), pdfBytes = bytes.size.toLong(),
            pageCount = 3, pdfSource = "https://example.org/tiny.pdf", layout = DocumentLayout(List(3) { PageBox(it, 612f, 792f) }, listOf(run(sources[0], 1500), run(sources[1], 2000))))
    }

    @Test fun `the exact file is accepted, shown as the real page at once, and kept for the next visit`() {
        val dir = File(tmp.root, "profile/source-documents")
        val presets = PresetSources(SourceLayouts { id -> presetLayout().takeIf { id == "demo_tiny" } }, SourceDocumentStore(dir))
        val picked = File(tmp.root, "Downloads/tiny.pdf").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        show(presets, "demo_tiny", "doc_demo_tiny", "sha256:tiny", sources, listOf(SourceRef("c2", quote)), Picker { picked })
        rule.waitUntil(5000) { has("source_attach_pdf") }
        rule.onNodeWithTag("source_attach_pdf").performClick()
        rule.waitUntil(5000) { has("source_pdf_page") }
        assertTrue(has("source_highlight_rect"))
        assertEquals("2 / 3", textOf("source_page_label"))
        assertMarkedWhereMeasured()
        assertTrue("the offer and the text-only sentence are gone once the page shows", !has("source_attach_pdf") && !has("source_text_note"))

        // Kept app-private, per profile, under the preset's own ids; the picked file itself is not referenced.
        val kept = awaitIo { presets.load("demo_tiny", "doc_demo_tiny", "sha256:tiny") }!!
        assertEquals(sha(bytes), kept.pdfSha256)
        assertTrue(kept.file.canonicalPath.startsWith(dir.canonicalPath) && kept.file.readBytes().contentEquals(bytes))
        assertEquals(3, kept.pageCount)
        assertEquals(presetLayout().layout, kept.layout)
        assertNull("another session's ids", awaitIo { presets.load("demo_tiny", "doc_demo_tiny", "sha256:other") })
        assertNotNull("what the session lookup by ids returns too", awaitIo { SourceDocumentStore(dir).load("doc_demo_tiny", "sha256:tiny") })
        // A later wrong file is refused and does not replace the good one.
        val wrong = "%PDF-1.7\n% a different file\n".toByteArray()
        assertTrue(awaitIo { presets.attach("demo_tiny") { wrong.inputStream() } } is AttachResult.Mismatch)
        assertTrue(awaitIo { presets.load("demo_tiny", "doc_demo_tiny", "sha256:tiny") }!!.file.readBytes().contentEquals(bytes))
        // Over the 20 MB cap: refused without holding more than the cap.
        val huge = object : java.io.InputStream() { var n = 0L; override fun read(): Int = if (n++ < 5) "%PDF-"[n.toInt() - 1].code else if (n < 21L * 1024 * 1024) 0 else -1 }
        assertEquals(AttachResult.TooLarge, awaitIo { presets.attach("demo_tiny") { huge } })
    }

    @Test fun `an attached preset PDF opens on the real page on the next visit, with no offer`() {
        val presets = PresetSources(SourceLayouts { presetLayout() }, SourceDocumentStore(File(tmp.root, "source-documents")))
        assertTrue(awaitIo { presets.attach("demo_tiny") { bytes.inputStream() } } is AttachResult.Accepted)
        show(presets, "demo_tiny", "doc_demo_tiny", "sha256:tiny", sources, listOf(SourceRef("c2", quote)), Picker { error("no picker") })
        rule.waitUntil(5000) { has("source_pdf_page") }
        assertTrue(has("source_highlight_rect") && !has("source_attach_pdf"))
    }

    /** The mark sits where the packed GlyphRun put the quote (top 2000..2250 of 10000, x from the quote's first glyph), on the shown page. */
    private fun assertMarkedWhereMeasured() {
        val width = rule.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.width }
        val mark = rule.onAllNodesWithTag("source_highlight_rect", useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("on the page in view, not a neighbour", mark.left >= 0f && mark.right <= width)
        val page = rule.onAllNodesWithTag("source_pdf_page", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.single { it.width > 0f && it.contains(mark.center) }
        val text = sources[1].text
        val at = text.indexOf(quote)
        val k = text.substring(0, at).count { !SourceLayout.isGap(it) }
        val m = quote.count { !SourceLayout.isGap(it) }
        fun near(expected: Float, actual: Float, what: String) = assertEquals(what, expected, actual, 0.004f)
        near(0.2f, (mark.top - page.top) / page.height, "top")
        near(0.225f, (mark.bottom - page.top) / page.height, "bottom")
        near((500 + k * 90) / 10000f, (mark.left - page.left) / page.width, "left")
        near((500 + (k + m - 1) * 90 + 80) / 10000f, (mark.right - page.left) / page.width, "right")
    }

    @Test fun `a file of exactly the measured size but other bytes is refused on its hash alone`() {
        val dir = File(tmp.root, "same-size")
        val presets = PresetSources(SourceLayouts { presetLayout() }, SourceDocumentStore(dir))
        // One byte flipped after the header: same length, still a PDF, another file.
        val twin = bytes.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1] + 1).toByte() }
        assertEquals(bytes.size, twin.size)
        assertTrue(awaitIo { presets.attach("demo_tiny") { twin.inputStream() } } is AttachResult.Mismatch)
        assertNull("nothing kept", awaitIo { SourceDocumentStore(dir).load("doc_demo_tiny", "sha256:tiny") })
        assertNull(awaitIo { presets.load("demo_tiny", "doc_demo_tiny", "sha256:tiny") })
    }

    @Test fun `a demo build's bundled paper is used with no attaching, and a bundled copy that does not match is not`() {
        val l = presetLayout()
        val good = PresetSources(SourceLayouts { id -> presetLayout().takeIf { id == "demo_tiny" } }, SourceDocumentStore(File(tmp.root, "bundled"))) { bytes.inputStream() }
        val first = awaitIo { good.load("demo_tiny", l.documentId, l.documentVersion) }
        assertNotNull("the bundled paper opens the real page on the first visit", first)
        assertEquals(sha(bytes), first!!.pdfSha256)
        assertTrue("and is kept as an ordinary file for the renderer", first.file.isFile)
        // A build that bundled another file (the source changed after measuring): never drawn on, and nothing kept.
        val other = bytes.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1] + 1).toByte() }
        val dir = File(tmp.root, "bundled-other")
        val bad = PresetSources(SourceLayouts { presetLayout() }, SourceDocumentStore(dir)) { other.inputStream() }
        assertNull(awaitIo { bad.load("demo_tiny", l.documentId, l.documentVersion) })
        assertNull(awaitIo { SourceDocumentStore(dir).load(l.documentId, l.documentVersion) })
        // A course with no packed layout gets nothing from a bundle either.
        assertNull(awaitIo { good.load("demo_other", l.documentId, l.documentVersion) })
    }

    @Test fun `a record under the preset's ids with another hash or none is never shown as the preset's page`() {
        val dir = File(tmp.root, "foreign")
        val store = SourceDocumentStore(dir)
        val presets = PresetSources(SourceLayouts { presetLayout() }, store)
        val l = presetLayout()
        // Pre-edition releases kept one file and one flat record under a hash of the document ID.
        check(dir.mkdirs())
        val base = sha(l.documentId.toByteArray())
        File(dir, "$base.pdf").writeBytes(bytes)
        val old = File(dir, "$base.layout.json")
        val fields = """"documentId":"${l.documentId}","documentVersion":"${l.documentVersion}","pageCount":${l.pageCount},"layout":${ContractJson.json.encodeToString(l.layout)}"""
        old.writeText("{$fields}")
        assertNull(awaitIo { presets.load("demo_tiny", l.documentId, l.documentVersion) })
        old.writeText("""{$fields,"pdfSha256":"${"0".repeat(64)}"}""")
        assertNull(awaitIo { presets.load("demo_tiny", l.documentId, l.documentVersion) })
        assertTrue(awaitIo { runCatching { store.save(l.documentId, l.documentVersion, l.pageCount, l.layout, bytes, "0".repeat(64)) }.isFailure })
        awaitIo { store.save(l.documentId, l.documentVersion, l.pageCount, l.layout, bytes, pdfSha256 = sha(bytes)) }
        assertNotNull("the measured file under the same ids is", awaitIo { presets.load("demo_tiny", l.documentId, l.documentVersion) })
    }

    @Test fun `closing the viewer while the file is checked still keeps the file for the next look`() {
        val presets = PresetSources(SourceLayouts { presetLayout() }, SourceDocumentStore(File(tmp.root, "closed")))
        val picked = File(tmp.root, "tiny.pdf").apply { writeBytes(bytes) }
        val gate = CompletableDeferred<Unit>()
        var open by mutableStateOf(true)
        val offer = AttachOffer(awaitIo { presets.available("demo_tiny") }!!) { o -> gate.await(); presets.attach("demo_tiny", o) }
        rule.setContent {
            A2gTheme {
                CompositionLocalProvider(LocalPageImages provides { FakePages(3) }, LocalActivityResultRegistryOwner provides Picker { picked }) {
                    // The caller drops the viewer as soon as it is dismissed, as every screen does.
                    if (open) SourceViewer(SourceFile("The paper", sources, null, offer), listOf(SourceRef("c2", quote)), onDismiss = { open = false })
                }
            }
        }
        rule.waitUntil(5000) { has("source_attach_pdf") }
        rule.onNodeWithTag("source_attach_pdf").performClick()
        rule.waitUntil(5000) { textOf("source_attach_status") == "Checking the file…" }
        rule.onNodeWithTag("source_attach_pdf").assertIsNotEnabled() // kept in place, not removed
        rule.onNodeWithTag("source_close").performClick()
        rule.waitUntil(5000) { !has("source_viewer") }
        gate.complete(Unit)
        // The check ran to the end although nobody was looking: the next look finds the file.
        rule.waitUntil(5000) { awaitIo { presets.attached("demo_tiny", "doc_demo_tiny", "sha256:tiny") } != null }
        assertEquals(sha(bytes), awaitIo { presets.attached("demo_tiny", "doc_demo_tiny", "sha256:tiny") }!!.pdfSha256)
    }

    // --- the real reading screen on the attention preset: its session finds the attachment, and keeps finding it ---
    private class WithSources(base: AppDependencies, override val sources: SourceLibrary) : AppDependencies by base

    /** Geometry for every chunk of [pkg], one line each, measured on [file]; stands in for the arXiv PDF this test cannot hold. */
    private fun measured(pkg: LearningPackage, file: ByteArray): PresetSourceLayout {
        val pages = pkg.sources.maxOf { it.pageIndex } + 1
        val chunks = pkg.sources.groupBy { it.pageIndex }.values.flatMap { onPage ->
            onPage.mapIndexed { i, c ->
                val n = c.text.count { !SourceLayout.isGap(it) }
                val edges = (0 until n).flatMap { j -> listOf(500 + j * 9000 / n, 500 + (j + 1) * 9000 / n) }
                ChunkLayout(c.chunkId, listOf(GlyphRun(0, 1000 + i * 2000, 2500 + i * 2000, SourceLayout.encode(edges))))
            }
        }
        return PresetSourceLayout(packageId = pkg.packageId, documentId = pkg.documentId, documentVersion = pkg.documentVersion, pdfSha256 = sha(file), pdfBytes = file.size.toLong(),
            pageCount = pages, pdfSource = "https://example.org/attention.pdf", layout = DocumentLayout(List(pages) { PageBox(it, 612f, 792f) }, chunks))
    }

    private fun tap(tag: String) { rule.onAllNodesWithTag(tag).onFirst().performClick(); rule.waitForIdle() }

    @Test fun `on the attention course the reading screen shows text until the PDF is attached, then the real page, also after recreation`() {
        val pkg = attention()
        val file = "%PDF-1.5\n% the reader's own copy\n".toByteArray()
        val presets = PresetSources(SourceLayouts { id -> measured(pkg, file).takeIf { id == "demo_attention" } }, SourceDocumentStore(File(tmp.root, "profile/source-documents")))
        val picked = File(tmp.root, "attention.pdf").apply { writeBytes(file) }
        val d = WithSources(deps, library(presets))
        val restore = StateRestorationTester(rule)
        restore.setContent {
            A2gTheme {
                CompositionLocalProvider(LocalPageImages provides { FakePages(15) }, LocalActivityResultRegistryOwner provides Picker { picked }) {
                    ReadingContent(d, "demo_attention", onBack = {})
                }
            }
        }
        // Through the course as a reader would, to the first explanation that cites the paper.
        rule.waitUntil(10000) { has("tutor_list") || has("explainer_stage") }
        fun tapListAction(tag: String): Boolean = try {
            rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).assertIsEnabled().performClick()
            true
        } catch (_: AssertionError) {
            // The previous asynchronous answer may have already transitioned to the next screen.
            false
        }
        rule.waitUntil(30000) {
            if (has("message_source")) return@waitUntil true
            when {
                has("explainer_stage") && has("dialogue_next") -> tap("dialogue_next")
                has("explainer_stage") && has("tutor_example") -> tap("tutor_example")
                has("explainer_stage") && has("dialogue_box") -> tap("dialogue_box")
                has("explainer_stage") -> rule.waitForIdle()
                has("tutor_list") -> listOf("start_tutor", "focus_continue", "answer_unknown").any(::tapListAction)
                else -> rule.waitForIdle()
            }
            false
        }

        tap("message_source")
        rule.waitUntil(5000) { has("source_viewer") && has("source_attach_pdf") }
        assertTrue("nothing attached: the text page", !has("source_pdf_page"))
        tap("source_attach_pdf")
        rule.waitUntil(5000) { has("source_pdf_page") }
        assertTrue(has("source_highlight_rect"))

        // Next look: the session's lookup by its ids finds the attachment, no offer.
        tap("source_close")
        rule.waitUntil(5000) { !has("source_viewer") }
        tap("message_source")
        rule.waitUntil(5000) { has("source_pdf_page") }
        assertTrue(!has("source_attach_pdf"))

        // Recreated (rotation, or the process killed behind the picker): the open viewer comes back on the real page.
        restore.emulateSavedInstanceStateRestore()
        rule.waitUntil(5000) { has("source_viewer") && has("source_pdf_page") }
        assertTrue(has("source_highlight_rect") && !has("source_attach_pdf"))
    }
}
