package com.anything2game.app

import com.anything2game.contract.tutor.TutorMode

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.data.learning.PdfImportItem
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.tutor.*
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.source.LocalPageImages
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.PageBox
import com.anything2game.contract.space.SpaceMember
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.serialization.builtins.ListSerializer
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

/** The Materials tab as a file list, its viewer, its text fallback, its empty state and large text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class SpaceFilesScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val out = "build/outputs/roborazzi/space-files"

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun wait(block: () -> Boolean) = rule.waitUntil(30000) { ShadowLooper.idleMainLooper(); runCatching(block).getOrDefault(false) }

    private fun show(deps: AppDependencies, nav: Navigator, pages: Int = 15, fontScale: Float? = null) = rule.setContent {
        A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(pages) }) {
            if (fontScale == null) AppRoot(deps, nav)
            else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { AppRoot(deps, nav) }
        } }
    }

    /** A guest Space of uploaded PDFs: each a lesson of the fixed example under its own name, with its PDF kept (or not). */
    private fun threePdfSpace(names: List<String>, pages: List<Int?>, addedDays: List<Int> = emptyList(), now: Long = System.currentTimeMillis()): Pair<AndroidAppContainer, String> {
        val deps = AndroidAppContainer(app)
        val spaceId = awaitIo {
            deps.profiles.choose(LocalProfile.GUEST)
            val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            val base = deps.tutors.open(pkg.packageId).cache.session
            val space = deps.spaces.create("Learning Lab")
            val rows = mutableListOf<PdfImportItem>()
            names.forEachIndexed { i, name ->
                val session = base.copy(sessionId = "tut_$i", documentId = "doc_file_$i", mode = TutorMode.MOCK, title = name)
                deps.tutors.save(TutorCache(session))
                pages[i]?.let { n -> SourceDocumentStore(File(app.filesDir, "source-documents")).save(session.documentId, session.documentVersion, n,
                    DocumentLayout(List(n) { PageBox(it, 612f, 792f) }), "%PDF-$i".toByteArray()) }
                deps.spaces.add(space.spaceId, SpaceMember.ServerDocumentRef(session.documentId, session.documentVersion))
                addedDays.getOrNull(i)?.let { rows += PdfImportItem("row$i", space.spaceId, name, "content://x/$i", 1_000_000, staged = true, sessionId = session.sessionId, addedAt = now - it * 86_400_000L) }
            }
            if (rows.isNotEmpty()) File(app.filesDir, "pdf-imports").apply { mkdirs() }.resolve("queue.json")
                .writeText(ContractJson.json.encodeToString(ListSerializer(PdfImportItem.serializer()), rows))
            space.spaceId
        }
        return deps to spaceId
    }

    private fun materials(deps: AppDependencies, id: String, fontScale: Float? = null, pages: Int = 15) {
        show(deps, Navigator(SavedStateHandle()).apply { openSpace(id, true) }, pages, fontScale)
        wait { rule.onAllNodes(hasTestTagPrefix("material_row_")).fetchSemanticsNodes().isNotEmpty() || has("materials_empty") }
        rule.waitForIdle()
    }

    private fun shot(name: String) { rule.onRoot().captureRoboImage("$out/$name.png") }

    @Test fun lin_attention_materials_en() {
        val deps = AndroidAppContainer(app)
        val id = awaitIo {
            deps.profiles.choose(LocalProfile.SAMPLE)
            val m = deps.materials.materials().first { it.localPack?.packageId == "demo_attention" }
            deps.spaces.list().first { m.member in it.members }.spaceId
        }
        materials(deps, id); shot("f1_lin_attention_materials_en")
    }

    @Test @Config(qualifiers = "zh-w393dp-h852dp-xxhdpi")
    fun three_pdfs_zh() {
        val (deps, id) = threePdfSpace(listOf("01-attention.pdf", "02-resnet.pdf", "03-tutor-notes.pdf"), listOf(15, 12, 8), listOf(2, 5, 40))
        materials(deps, id); shot("f2_three_pdfs_zh")
    }

    @Test fun a_file_with_a_menu_open_en() {
        val (deps, id) = threePdfSpace(listOf("01-attention.pdf", "02-resnet.pdf"), listOf(15, 12), listOf(2, 5))
        materials(deps, id)
        rule.onNodeWithTag("material_more_doc_file_0").performClick()
        rule.waitForIdle()
        captureScreenRoboImage("$out/f3_menu_en.png")
    }

    @Test fun pdf_viewer_on_a_page_en() {
        val (deps, id) = threePdfSpace(listOf("01-attention.pdf"), listOf(15))
        materials(deps, id)
        rule.onNodeWithTag("material_row_doc_file_0").performClick()
        wait { has("source_pdf_page") }
        repeat(2) { rule.onNodeWithTag("source_next").performClick(); rule.waitForIdle() }
        wait { has("source_pdf_page") }
        rule.waitForIdle()
        captureScreenRoboImage("$out/f4_pdf_viewer_en.png")
    }

    @Test fun text_fallback_en() {
        val (deps, id) = threePdfSpace(listOf("03-tutor-notes.pdf"), listOf(null))
        materials(deps, id)
        rule.onNodeWithTag("material_row_doc_file_0").performClick()
        wait { has("source_text_note") }
        rule.waitForIdle()
        captureScreenRoboImage("$out/f5_text_fallback_en.png")
    }

    @Test fun empty_state_en() {
        val deps = AndroidAppContainer(app)
        val id = awaitIo { deps.profiles.choose(LocalProfile.GUEST); deps.spaces.create("Biology notes").spaceId }
        materials(deps, id); shot("f6_empty_en")
    }

    @Test @Config(qualifiers = "zh-w393dp-h852dp-xxhdpi")
    fun empty_state_zh() {
        val deps = AndroidAppContainer(app)
        val id = awaitIo { deps.profiles.choose(LocalProfile.GUEST); deps.spaces.create("生物笔记").spaceId }
        materials(deps, id); shot("f6b_empty_zh")
    }

    @Test fun font_scale_two_en() {
        val (deps, id) = threePdfSpace(listOf("01-attention.pdf", "A long file name for a lecture on attention and memory.pdf"), listOf(15, 12), listOf(2, 5))
        materials(deps, id, fontScale = 2f); shot("f7_font2_en")
    }

    @Test fun font_scale_two_viewer_en() {
        val (deps, id) = threePdfSpace(listOf("A long file name for a lecture on attention and memory.pdf"), listOf(15))
        materials(deps, id, fontScale = 2f)
        rule.onNodeWithTag("material_row_doc_file_0").performClick()
        wait { has("source_pdf_page") }
        rule.waitForIdle()
        captureScreenRoboImage("$out/f8_font2_viewer_en.png")
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
