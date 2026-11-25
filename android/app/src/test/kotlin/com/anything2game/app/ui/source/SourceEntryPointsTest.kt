package com.anything2game.app.ui.source

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.DrawnPages
import com.anything2game.app.R
import com.anything2game.app.awaitIo
import com.anything2game.app.data.learning.Material
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.source.CourseRef
import com.anything2game.app.data.source.Original
import com.anything2game.app.data.source.OriginalResolver
import com.anything2game.app.data.source.ResolvingSourceLibrary
import com.anything2game.app.data.source.SourceFile
import com.anything2game.app.data.source.SourceKey
import com.anything2game.app.data.source.SourceText
import com.anything2game.app.data.tutor.EXAMPLE_TUTOR_PACKAGE
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.app.data.tutor.SourceDocumentStore
import com.anything2game.app.data.tutor.TutorCache
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.FrameId
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PageBox
import com.anything2game.contract.game.EvidenceRef
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.space.SpaceMember
import com.anything2game.contract.tutor.TutorIntent
import com.anything2game.contract.tutor.UNKNOWN_ANSWER
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.LevelSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

/**
 * Five ways to open a source (Practice evidence, the explainer, a file in Materials, a route step, the source route) and
 * one viewer behind all of them: the same root tag, the original's real pages at the cited page when the phone has the
 * file, and the labelled text layout when it does not. The original is injected the way the app finds it (a PDF kept for
 * the document), its pages through the `LocalPageImages` seam because Robolectric cannot render a PDF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceEntryPointsTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun wait(what: String, block: () -> Boolean) = rule.waitUntil(30000) { ShadowLooper.idleMainLooper(); runCatching(block).getOrDefault(false) }
    private fun textOf(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun mergedTextOf(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun container() = AndroidAppContainer(app).also { awaitIo { it.profiles.choose(LocalProfile.GUEST) } }

    /** The reader's own PDF of this document kept on the phone: the same record an import leaves. */
    private fun keepOriginal(documentId: String, documentVersion: String, pages: Int) = awaitIo {
        SourceDocumentStore(File(app.filesDir, "source-documents")).save(documentId, documentVersion, pages, DocumentLayout(List(pages) { PageBox(it, 612f, 792f) }), "%PDF-kept".toByteArray())
    }

    /** One assertion for every entry point: this is the shared viewer, showing [page] (0-based) of [pages]. */
    private fun assertViewer(original: Boolean, page: Int, pages: Int, label: String = (page + 1).toString()) {
        wait("the shared viewer") { has("source_viewer") && (has("source_pdf_page") || has("source_text_note")) }
        assertEquals("exactly one viewer", 1, rule.onAllNodesWithTag("source_viewer", useUnmergedTree = true).fetchSemanticsNodes().size)
        if (original) {
            assertEquals("${page + 1} / $pages", textOf("source_page_label"))
            assertFalse("real pages, not extracted text", has("source_text_note") || has("source_text_heading"))
        } else {
            assertEquals(app.getString(R.string.source_text_only_note), textOf("source_text_note"))
            assertEquals(app.getString(R.string.evidence_page, label), textOf("source_text_heading"))
            assertFalse(has("source_pdf_page"))
        }
    }

    // ---- 1. Practice: "Show evidence" ----------------------------------------------------------------------------

    private fun practice(original: Boolean) {
        val deps = container()
        val pkg = awaitIo { deps.packages.load("demo_attention").getOrThrow() }
        if (original) keepOriginal(pkg.documentId, pkg.documentVersion, 15)
        val (ready, cited) = practiceEvidence(pkg)
        rule.setContent {
            A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(15) }) {
                Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { PlayContent(ready, {}, {}, {}, sources = deps.sources) }
            } }
        }
        assertViewer(original, cited.pageIndex, 15, cited.pageLabel ?: (cited.pageIndex + 1).toString())
    }

    @Test fun practice_evidence_opens_the_shared_viewer_on_the_original() = practice(original = true)
    @Test fun practice_evidence_opens_the_shared_viewer_on_the_text_when_there_is_no_original() = practice(original = false)

    // ---- 2. The explainer: "Show source" -------------------------------------------------------------------------

    private fun explainer(original: Boolean) {
        val deps = container()
        val pkgId = "demo_attention"
        val pkg = awaitIo { deps.packages.load(pkgId).getOrThrow() }
        if (original) keepOriginal(pkg.documentId, pkg.documentVersion, 15)
        awaitIo {
            var cache = deps.tutors.open(pkgId).cache
            cache = deps.tutors.setFocus(cache, emptyList(), "")
            cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER }, questionIndex = cache.session.questions.lastIndex)
            cache = cache.copy(session = deps.tutors.assess(cache, "a"))
            cache = cache.copy(session = deps.tutors.turn(cache, TutorIntent.START, "", "s"))
            deps.tutors.save(cache)
        }
        rule.setContent { A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(15) }) { ReadingContent(deps, pkgId, onBack = {}) } } }
        wait("the explanation") { has("dialogue_text") }
        // The chip sits under the last page of the explanation.
        repeat(40) { if (!has("tutor_rephrase")) { rule.onNodeWithTag("dialogue_next").performClick(); rule.waitForIdle() } }
        wait("the chip") { has("message_source") }
        // "Show source · p.3": the page the explainer says it rests on is the page the viewer opens on.
        val said = Regex("""p\.(\d+)""").find(mergedTextOf("message_source")).let { requireNotNull(it) { "chip: " + mergedTextOf("message_source") } }.groupValues[1].toInt()
        rule.onNodeWithTag("message_source").performClick()
        assertViewer(original, said - 1, 15, said.toString())
    }

    @Test fun the_explainer_opens_the_shared_viewer_on_the_original() = explainer(original = true)
    @Test fun the_explainer_opens_the_shared_viewer_on_the_text_when_there_is_no_original() = explainer(original = false)

    // ---- 3 and 4. Space: a file in Materials, a source chip on the route -----------------------------------------------

    private class Seeded(val deps: AndroidAppContainer, val spaceId: String, val session: com.anything2game.contract.tutor.TutorSession)

    private fun seedUpload(original: Boolean, pages: Int): Seeded {
        val deps = AndroidAppContainer(app)
        return awaitIo {
            deps.profiles.choose(LocalProfile.GUEST)
            val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            val session = deps.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_file", mode = "mock", title = "lecture-notes.pdf")
            deps.tutors.save(TutorCache(session))
            if (original) SourceDocumentStore(File(app.filesDir, "source-documents")).save(session.documentId, session.documentVersion, pages,
                DocumentLayout(List(pages) { PageBox(it, 612f, 792f) }), "%PDF-kept".toByteArray())
            val space = deps.spaces.list().single { it.members == listOf(SpaceMember.ServerDocumentRef(session.documentId, session.documentVersion)) }
            Seeded(deps, space.spaceId, session)
        }
    }

    private fun showApp(deps: AndroidAppContainer, nav: Navigator, pages: Int) = rule.setContent {
        A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(pages) }) { AppRoot(deps, nav) } }
    }

    private fun materials(original: Boolean) {
        val s = seedUpload(original, pages = 9)
        showApp(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) }, 9)
        wait("the row") { has("material_row_${s.session.documentId}") }
        rule.onNodeWithTag("material_row_${s.session.documentId}").performClick()
        assertViewer(original, page = 0, pages = 9)
        assertEquals("lecture-notes.pdf", textOf("source_title"))
    }

    @Test fun a_file_in_materials_opens_the_shared_viewer_on_the_original() = materials(original = true)
    @Test fun a_file_in_materials_opens_the_shared_viewer_on_the_text_when_there_is_no_original() = materials(original = false)

    private fun routeStep(original: Boolean) {
        val s = seedUpload(original, pages = 9)
        val material: Material = awaitIo { s.deps.materials.materials().first { it.tutorKey == "tut_file" } }
        val node = material.nodes.first { it.activity is ActivityRef.Tutor && it.evidence.isNotEmpty() }
        val ref = node.evidence.filterIsInstance<EvidenceRef.Pdf>().first()
        showApp(s.deps, Navigator(SavedStateHandle()).apply { openSpace(s.spaceId) }, 9)
        wait("the node") { rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_${node.nodeId}")); true }
        rule.onNodeWithTag("route_node_${node.nodeId}").performClick()
        wait("the chip") { has("route_sheet_source") }
        rule.onNodeWithTag("route_sheet_source").performClick()
        assertViewer(original, ref.pageIndex, 9, ref.pageLabel ?: (ref.pageIndex + 1).toString())
    }

    @Test fun a_route_step_opens_the_shared_viewer_on_the_original_at_the_cited_page() = routeStep(original = true)
    @Test fun a_route_step_opens_the_shared_viewer_on_the_text_at_the_cited_page() = routeStep(original = false)

    // ---- 5. The source route -----------------------------------------------------------------------------------------

    private fun sourceRoute(original: Boolean) {
        val deps = container()
        val pkg = awaitIo { deps.packages.load("demo_attention").getOrThrow() }
        if (original) keepOriginal(pkg.documentId, pkg.documentVersion, 15)
        showApp(deps as AndroidAppContainer, Navigator(SavedStateHandle()).apply { openSource(pkg.packageId, pkg.contentVersion) }, 15)
        assertViewer(original, page = 0, pages = 15, label = pkg.sources.minBy { it.pageIndex }.let { it.pageLabel ?: (it.pageIndex + 1).toString() })
    }

    @Test fun the_source_route_opens_the_shared_viewer_on_the_original() = sourceRoute(original = true)
    @Test fun the_source_route_opens_the_shared_viewer_on_the_text_when_there_is_no_original() = sourceRoute(original = false)

    // ---- the structure: a new kind of original is a resolver, and no screen knows ---------------------------------------

    @Test fun a_resolver_added_to_the_library_changes_what_every_screen_shows_with_no_screen_change() {
        val pkg = LearningPackageStub.sources
        val file = File(app.cacheDir, "extra.pdf").apply { writeBytes(byteArrayOf(1)) }
        val extra = OriginalResolver { Original.Pdf(SourceDocument("d", "v", 4, file, DocumentLayout(List(4) { PageBox(it, 612f, 792f) }))) }
        val library = ResolvingSourceLibrary(listOf(extra))
        rule.setContent {
            A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(4) }) {
                OpenSourceViewer(library, SourceKey("d", "v", CourseRef("c")), SourceText("Deck", pkg), onDismiss = {})
            } }
        }
        wait("the extra original") { has("source_pdf_page") }
        assertEquals("1 / 4", textOf("source_page_label"))
    }

    @Test fun nothing_of_the_old_fake_pages_is_left() {
        val ui = File("src/main/kotlin/com/anything2game/app/ui")
        val banned = listOf("PageSheet", "TextSourceViewer", "TextDocument", "EvidencePanel", "MaterialReader", "DocumentViewer", "PdfSourceViewer")
        val offenders = ui.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> banned.filter { it in f.readText() }.map { "${f.name}: $it" } }.toList()
        assertTrue("old viewers are gone: $offenders", offenders.isEmpty())
        // One viewer: only the source package opens a dialog around sources.
        val viewers = ui.walkTopDown().filter { it.extension == "kt" && "SOURCE_VIEWER_TAG" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("SourceViewer.kt"), viewers)
    }

    private object LearningPackageStub {
        val sources = listOf(com.anything2game.contract.SourceChunk("c1", 0, "1", "A slide deck exported as a PDF."))
    }
}
