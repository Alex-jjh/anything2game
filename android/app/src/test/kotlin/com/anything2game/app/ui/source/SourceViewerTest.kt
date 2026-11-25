package com.anything2game.app.ui.source

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.anything2game.app.data.source.Original
import com.anything2game.app.data.source.SourceFile
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.app.data.tutor.SourceDocumentStore
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.*
import com.anything2game.app.awaitIo
import org.junit.Assert.assertEquals
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

/** The one source viewer: the real PDF page with the quote marked when the original is on the phone, the extracted text (plainly labelled) otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceViewerTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Before fun still() {
        val app = RuntimeEnvironment.getApplication()
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private val sources = listOf(
        SourceChunk("c1", 0, "1", "Retrieval practice means pulling an idea out of memory instead of reading it again."),
        SourceChunk("c2", 1, "2", "Spaced practice beats cramming because forgetting between sessions makes each retrieval harder."),
    )
    private val quote = "forgetting between sessions makes each retrieval"
    private val refs = listOf(SourceRef("c2", quote))

    /** c2 printed on two lines of page 2: its first 40 non-whitespace characters, then the rest. */
    private fun layout(): DocumentLayout {
        val n = sources[1].text.count { !SourceLayout.isGap(it) }
        fun run(first: Int, count: Int, top: Int) = GlyphRun(first, top, top + 250, SourceLayout.encode((0 until count).flatMap { listOf(1000 + it * 180, 1000 + it * 180 + 170) }))
        return DocumentLayout(List(3) { PageBox(it, 612f, 792f) }, listOf(ChunkLayout("c2", listOf(run(0, 40, 2000), run(40, n - 40, 2300)))))
    }

    private class FakePages(override val pageCount: Int) : PageImages {
        val rendered = mutableListOf<Int>()
        var closed = false
        override suspend fun render(index: Int, widthPx: Int): ImageBitmap { rendered += index; return ImageBitmap(306, 396) }
        override fun close() { closed = true }
    }

    private fun document(pages: Int = 3) = SourceDocument("doc", "v1", pages, File(tmp.root, "doc.pdf").apply { writeBytes(byteArrayOf(1)) }, layout())
    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun show(pdf: SourceDocument?, cited: List<SourceRef> = refs, startPage: Int? = null, open: suspend (File) -> PageImages) = rule.setContent {
        A2gTheme { CompositionLocalProvider(LocalPageImages provides open) { SourceViewer(SourceFile("Lecture 3", sources, pdf?.let(Original::Pdf)), cited, startPage, onDismiss = {}) } }
    }

    @Test fun `without the original the text is a reading layout with one plain sentence, not a page of paper`() {
        var opened = 0
        show(null) { opened++; FakePages(3) }
        rule.waitUntil(5000) { has("source_highlighted") }
        assertTrue(textOf("source_highlighted").contains(quote))
        assertEquals("The original file isn't on this phone, so this is its text.", textOf("source_text_note"))
        // Opens on the cited chunk's page, headed like a reading layout; the title bar names the file.
        assertEquals("Page 2", textOf("source_text_heading"))
        assertEquals("Lecture 3", textOf("source_title"))
        assertEquals("2 / 2", textOf("source_page_label"))
        assertTrue(!has("source_pdf_page") && !has("source_highlight_rect"))
        assertEquals(0, opened)
        rule.onNodeWithTag("source_prev").performClick()
        rule.waitUntil(5000) { textOf("source_text_heading") == "Page 1" }
        assertTrue(has("source_text"))
    }

    @Test fun `a wiki-style heading in the extracted text shows as a heading without its equals signs`() {
        val chunks = listOf(SourceChunk("c1", 0, "1", "Intro line.\n== 研究和应用 ==\nBody text that follows."))
        rule.setContent { A2gTheme { SourceViewer(SourceFile("Wiki page", chunks, null), listOf(SourceRef("c1", "Body text that follows")), null, onDismiss = {}) } }
        rule.waitUntil(5000) { has("source_highlighted") }
        val shown = textOf("source_highlighted")
        assertEquals("Intro line.\n研究和应用\nBody text that follows.", shown)
        assertTrue("no marks are left on the screen", !shown.contains("=="))
    }

    @Test fun `a caller's start page wins over the cited one`() {
        show(null, startPage = 0) { FakePages(3) }
        rule.waitUntil(5000) { has("source_text_heading") }
        assertEquals("Page 1", textOf("source_text_heading"))
    }

    @Test fun `while the file is being looked up the same frame shows a spinner and the name`() {
        rule.setContent { A2gTheme { SourceViewer(null, onDismiss = {}, loadingTitle = "Lecture 3") } }
        rule.waitUntil(5000) { has("source_loading") }
        assertTrue(has("source_viewer"))
        assertEquals("Lecture 3", textOf("source_title"))
    }

    private fun assertText(pdf: SourceDocument, note: String, open: suspend (File) -> PageImages) {
        show(pdf, open = open)
        rule.waitUntil(5000) { has("source_highlighted") }
        assertEquals(note, textOf("source_text_note"))
        assertTrue(!has("source_pdf_page") && !has("source_highlight_rect"))
    }

    @Test fun `the original is shown even when the quote cannot be placed on it, opened on the cited page`() {
        val bare = document().let { SourceDocument(it.documentId, it.documentVersion, 3, it.file, it.layout.copy(chunks = emptyList())) }
        show(bare) { FakePages(3) }
        rule.waitUntil(5000) { has("source_pdf_page") }
        assertEquals("2 / 3", textOf("source_page_label"))
        assertTrue(!has("source_highlight_rect") && !has("source_text_note"))
        assertTrue("the quote is still said in words", textOf("source_quote").startsWith("Quoted on this page"))
    }

    @Test fun `a PDF that will not open shows the text and says so`() = assertText(document(), "This file couldn't be opened here, so this is its text.") { throw java.io.IOException("damaged") }

    @Test fun `a PDF with another page count than the server read shows the text, and is closed`() {
        val pages = FakePages(7)
        assertText(document(), "This file couldn't be opened here, so this is its text.") { pages }
        assertTrue(pages.closed)
    }

    @Test fun `the kept PDF opens on the cited page with the quote marked line by line`() {
        val pages = FakePages(3)
        show(document()) { pages }
        rule.waitUntil(5000) { has("source_pdf_page") }
        assertEquals("2 / 3", textOf("source_page_label"))
        // The neighbours are composed too (one swipe away); the one on screen is page 2, described for TalkBack.
        rule.onNode(hasTestTag("source_pdf_page") and hasContentDescription("Original PDF, page 2 of 3")).assertIsDisplayed()
        // The quote wraps from the first printed line onto the second: one mark per line.
        val marks = rule.onAllNodesWithTag("source_highlight_rect").fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
        assertEquals(2, marks.size)
        // Each mark sits where the layout says on the page shown, not merely exists: compared in pixels to the page's bounds.
        val page = rule.onNode(hasTestTag("source_pdf_page") and hasContentDescription("Original PDF, page 2 of 3")).fetchSemanticsNode().boundsInRoot
        val expected = SourceLayout.rects(sources[1], layout().chunks[0], quote).sortedBy { it.top }
        assertEquals(2, expected.size)
        marks.zip(expected).forEach { (m, r) ->
            assertEquals(page.left + page.width * r.left, m.left, 1.5f)
            assertEquals(page.top + page.height * r.top, m.top, 1.5f)
            assertEquals(page.width * (r.right - r.left), m.width, 1.5f)
            assertEquals(page.height * (r.bottom - r.top), m.height, 1.5f)
        }
        assertTrue(rule.onAllNodesWithTag("source_quote").fetchSemanticsNodes().isNotEmpty())
        assertTrue(textOf("source_quote").contains(quote))
        assertTrue(1 in pages.rendered)

        rule.onNodeWithTag("source_close").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("◂ Prev").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("Next ▸").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5000) { textOf("source_page_label") == "3 / 3" }
        rule.onNodeWithText("◂ Prev").performClick(); rule.waitForIdle()
        rule.onNodeWithText("◂ Prev").performClick()
        rule.waitUntil(5000) { textOf("source_page_label") == "1 / 3" }
        rule.waitUntil(5000) { 0 in pages.rendered }
    }

    @Test fun `a quote on the page that could not be placed is captioned as quoted, not as marked`() {
        val lost = "a sentence the layout cannot place"
        show(document(), cited = refs + SourceRef("c2", lost)) { FakePages(3) }
        rule.waitUntil(5000) { has("source_pdf_page") }
        val captions = rule.onAllNodesWithTag("source_quote").fetchSemanticsNodes().map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }
        assertEquals(2, captions.size)
        assertTrue(captions[0], captions[0].startsWith("Marked on this page") && captions[0].contains(quote))
        assertTrue(captions[1], captions[1].startsWith("Quoted on this page") && captions[1].contains(lost))
    }

    @Test fun `the store keeps the PDF with its layout for exactly that document version`() = awaitIo {
        val store = SourceDocumentStore(File(tmp.root, "source-documents"))
        assertNull(store.load("doc", "v1"))
        val bytes = "%PDF-1.7 test".toByteArray()
        store.save(DocumentResponse("doc", "v1", 3, "Title", layout()), bytes)
        val kept = store.load("doc", "v1")!!
        assertEquals(3, kept.pageCount)
        assertEquals(layout(), kept.layout)
        assertTrue(kept.file.readBytes().contentEquals(bytes))
        assertNull("another version of the document", store.load("doc", "v2"))
        assertNull(store.load("other", "v1"))
        kept.file.delete()
        assertNull("record without its file", store.load("doc", "v1"))
    }
}
