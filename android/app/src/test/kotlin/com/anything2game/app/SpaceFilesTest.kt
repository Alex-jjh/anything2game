package com.anything2game.app

import com.anything2game.contract.tutor.TutorMode

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.data.learning.*
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.tutor.*
import com.anything2game.app.ui.*
import com.anything2game.app.ui.source.LocalPageImages
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.PageBox
import com.anything2game.contract.game.EvidenceRef
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.space.SpaceMember
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** The Materials tab as a phone's file list: what each row says, what a tap opens, what the menu keeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpaceFilesTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun idle() = ShadowLooper.idleMainLooper()
    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun wait(what: String, block: () -> Boolean) = rule.waitUntil(20000) { idle(); runCatching(block).getOrDefault(false) }
    private fun textOf(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** A guest's Space holding one lesson of the fixed example, saved the way an uploaded PDF is (a session named like the file). */
    private class Seeded(val deps: AndroidAppContainer, val spaceId: String, val session: com.anything2game.contract.tutor.TutorSession)

    private fun seedUpload(fileName: String, keepPdfPages: Int? = null): Seeded {
        val deps = AndroidAppContainer(app)
        return awaitIo {
            deps.profiles.choose(LocalProfile.GUEST)
            val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            val session = deps.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_file", mode = TutorMode.MOCK, title = fileName)
            deps.tutors.save(TutorCache(session))
            if (keepPdfPages != null) SourceDocumentStore(File(app.filesDir, "source-documents")).save(session.documentId, session.documentVersion, keepPdfPages,
                DocumentLayout(List(keepPdfPages) { PageBox(it, 612f, 792f) }), "%PDF-kept".toByteArray())
            val space = deps.spaces.list().single { it.members == listOf(SpaceMember.ServerDocumentRef(session.documentId, session.documentVersion)) }
            Seeded(deps, space.spaceId, session)
        }
    }

    private fun show(deps: AppDependencies, nav: Navigator, pages: Int? = null) = rule.setContent {
        A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(pages ?: 3) }) { AppRoot(deps, nav) } }
    }

    private fun ready(tag: String) = wait("$tag shown") { rule.onNodeWithTag(tag).assertExists(); true }

    @Test fun the_list_shows_each_file_with_its_type_name_and_plain_facts() {
        val s = seedUpload("lecture-notes.pdf", keepPdfPages = 15)
        show(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) })
        ready("material_row_${s.session.documentId}")
        wait("facts") { has("material_facts_${s.session.documentId}") }
        assertEquals("lecture-notes.pdf", textOf("material_title_${s.session.documentId}"))
        assertEquals("PDF · 15 pages", textOf("material_facts_${s.session.documentId}"))
        // No status when nothing is wrong, and no developer words.
        assertFalse(has("material_status_${s.session.documentId}"))
    }

    @Test fun a_file_added_through_the_space_says_when() {
        val s = seedUpload("lecture-notes.pdf", keepPdfPages = 15)
        val row = PdfImportItem("r1", s.spaceId, "lecture-notes.pdf", "content://x/1", 1_000, staged = true, sessionId = "tut_file", addedAt = System.currentTimeMillis() - 3 * 86_400_000L)
        File(app.filesDir, "pdf-imports").apply { mkdirs() }.resolve("queue.json")
            .writeText(com.anything2game.contract.ContractJson.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PdfImportItem.serializer()), listOf(row)))
        show(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) })
        ready("material_row_${s.session.documentId}")
        wait("the day") { textOf("material_facts_${s.session.documentId}").contains(app.getString(R.string.file_added, "").trim().substringBefore(" ")) }
        assertTrue(textOf("material_facts_${s.session.documentId}").startsWith("PDF · 15 pages · added "))
    }

    @Test fun tapping_a_file_opens_the_original_pdf_at_page_one_and_back_returns_to_the_list() {
        val s = seedUpload("lecture-notes.pdf", keepPdfPages = 15)
        show(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) }, pages = 15)
        ready("material_row_${s.session.documentId}")
        rule.onNodeWithTag("material_row_${s.session.documentId}").performClick()
        wait("the PDF page") { has("source_pdf_page") }
        assertEquals("lecture-notes.pdf", textOf("source_title"))
        assertEquals("1 / 15", textOf("source_page_label"))
        assertFalse("a plain file has no quote marks", has("source_highlight_rect"))
        rule.onNodeWithTag("source_next").performClick()
        wait("page 2") { textOf("source_page_label") == "2 / 15" }
        rule.onNodeWithTag("source_close").performClick()
        wait("the list again") { !has("source_viewer") && has("material_row_${s.session.documentId}") }
    }

    @Test fun without_the_original_file_the_viewer_says_so_and_shows_the_text_by_page() {
        val s = seedUpload("lecture-notes.pdf")
        show(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) })
        ready("material_row_${s.session.documentId}")
        rule.onNodeWithTag("material_row_${s.session.documentId}").performClick()
        wait("the text") { has("source_text_note") }
        assertEquals(app.getString(R.string.source_text_only_note), textOf("source_text_note"))
        assertFalse(has("source_pdf_page"))
        val pages = s.session.sources.map { it.pageIndex }.distinct().size
        assertEquals("1 / $pages", textOf("source_page_label"))
        assertTrue(has("source_text") || has("source_highlighted"))
    }

    @Test fun a_file_that_is_not_a_pdf_says_it_is_text() {
        val s = seedUpload("Study notes")
        show(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) })
        ready("material_row_${s.session.documentId}")
        wait("facts") { has("material_facts_${s.session.documentId}") }
        assertTrue(textOf("material_facts_${s.session.documentId}").startsWith(app.getString(R.string.file_kind_text)))
    }

    @Test fun a_source_chip_on_the_route_opens_the_same_viewer_on_the_cited_page() {
        val s = seedUpload("lecture-notes.pdf", keepPdfPages = 9)
        val material = awaitIo { s.deps.materials.materials().first { it.tutorKey == "tut_file" } }
        val node = material.nodes.first { it.activity is ActivityRef.Tutor && it.evidence.isNotEmpty() }
        val ref = node.evidence.filterIsInstance<EvidenceRef.Pdf>().first()
        val nav = Navigator(SavedStateHandle()).apply { openSpace(s.spaceId) }
        show(s.deps, nav, pages = 9)
        wait("the node") { rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_${node.nodeId}")); true }
        rule.onNodeWithTag("route_node_${node.nodeId}").performClick()
        wait("the chip") { has("route_sheet_source") }
        rule.onNodeWithTag("route_sheet_source").performClick()
        wait("the page") { has("source_pdf_page") }
        assertEquals("lecture-notes.pdf", textOf("source_title"))
        assertEquals("${ref.pageIndex + 1} / 9", textOf("source_page_label"))
    }

    @Test fun the_menu_keeps_explain_remove_and_practice_actions() {
        val s = seedUpload("lecture-notes.pdf", keepPdfPages = 4)
        val nav = Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) }
        show(s.deps, nav)
        val id = s.session.documentId
        ready("material_more_$id")
        // Explain opens the lesson.
        rule.onNodeWithTag("material_more_$id").performClick()
        rule.onNodeWithTag("start_explanation_tut_file").performClick()
        assertEquals("tut_file", (nav.route.value as Route.Read).packageId)
    }

    @Test fun removing_a_file_takes_it_out_of_the_list() {
        val s = seedUpload("lecture-notes.pdf", keepPdfPages = 4)
        show(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) })
        val id = s.session.documentId
        ready("material_more_$id")
        rule.onNodeWithTag("material_more_$id").performClick()
        rule.onNodeWithTag("remove_$id").performClick()
        wait("empty list") { has("materials_empty") }
        assertTrue(awaitIo { s.deps.spaces.list().first { it.spaceId == s.spaceId }.members.isEmpty() })
    }

    @Test fun an_uploaded_file_without_a_course_offers_to_make_practice_questions() {
        val s = seedUpload("lecture-notes.pdf", keepPdfPages = 4)
        show(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) })
        val id = s.session.documentId
        ready("material_more_$id")
        rule.onNodeWithTag("material_more_$id").performClick()
        rule.onNodeWithTag("prepare_practice_$id").assertExists().assertHasClickAction()
        rule.onNodeWithTag("practise_$id").assertDoesNotExist()
    }

    @Test fun a_course_in_the_space_unfolds_its_practice_from_the_menu() {
        val deps = AndroidAppContainer(app)
        val (spaceId, pkgId) = awaitIo {
            deps.profiles.choose(LocalProfile.SAMPLE)
            val m = deps.materials.materials().first { it.localPack?.packageId == "demo_attention" }
            deps.spaces.list().first { m.member in it.members }.spaceId to "demo_attention"
        }
        show(deps, Navigator(SavedStateHandle()).apply { openSpace(spaceId, true) })
        ready("material_more_$pkgId")
        assertFalse(has("practice_$pkgId"))
        rule.onNodeWithTag("material_more_$pkgId").performClick()
        rule.onNodeWithTag("practise_$pkgId").performClick()
        wait("practice list") { has("practice_$pkgId") && rule.onAllNodes(hasTestTagStartingWith("play_${pkgId}_")).fetchSemanticsNodes().isNotEmpty() }
        // The row of a course with a paper reads as that paper.
        assertTrue(textOf("material_facts_$pkgId").matches(Regex("PDF · \\d+ pages")))
    }

    @Test fun a_space_with_no_files_shows_the_character_and_one_add_button() {
        val deps = AndroidAppContainer(app)
        val id = awaitIo { deps.profiles.choose(LocalProfile.GUEST); deps.spaces.create("Biology notes").spaceId }
        show(deps, Navigator(SavedStateHandle()).apply { openSpace(id, true) })
        ready("materials_empty")
        rule.onNode(hasTestTag("materials_empty") and hasAnyDescendant(hasText(app.getString(R.string.files_empty_title)))).assertExists()
        rule.onNodeWithTag("pick_pdfs").assertHasClickAction()
        assertEquals(1, rule.onAllNodesWithTag("pick_pdfs").fetchSemanticsNodes().size)
    }

    private fun hasTestTagStartingWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
