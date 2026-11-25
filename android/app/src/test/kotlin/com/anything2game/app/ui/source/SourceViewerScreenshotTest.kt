package com.anything2game.app.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.DrawnPages
import com.anything2game.app.awaitIo
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.tutor.EXAMPLE_TUTOR_PACKAGE
import com.anything2game.app.data.tutor.SourceDocumentStore
import com.anything2game.app.data.tutor.TutorCache
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.PageBox
import com.anything2game.contract.space.SpaceMember
import com.github.takahirom.roborazzi.captureScreenRoboImage
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

/** The one source viewer, from Practice and from Materials, and the text layout when the original is not on the phone (EN and ZH). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceViewerScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val out = "build/outputs/roborazzi/source-viewer"

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun wait(block: () -> Boolean) = rule.waitUntil(30000) { ShadowLooper.idleMainLooper(); runCatching(block).getOrDefault(false) }
    private fun container() = AndroidAppContainer(app).also { awaitIo { it.profiles.choose(LocalProfile.GUEST) } }
    private fun keep(id: String, version: String, pages: Int) = awaitIo {
        SourceDocumentStore(File(app.filesDir, "source-documents")).save(id, version, pages, DocumentLayout(List(pages) { PageBox(it, 612f, 792f) }), "%PDF-kept".toByteArray())
    }

    private fun practice(pkgId: String, original: Boolean, shot: String) {
        val deps = container()
        val pkg = awaitIo { deps.packages.load(pkgId).getOrThrow() }
        if (original) keep(pkg.documentId, pkg.documentVersion, 15)
        val (ready, _) = practiceEvidence(pkg)
        rule.setContent {
            A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(15) }) {
                Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { PlayContent(ready, {}, {}, {}, sources = deps.sources) }
            } }
        }
        wait { has(if (original) "source_pdf_page" else "source_text_note") }
        rule.waitForIdle()
        captureScreenRoboImage("$out/$shot.png")
    }

    @Test fun practice_evidence_original_en() = practice("demo_attention", original = true, shot = "01_practice_evidence_original_en")
    @Test fun practice_evidence_text_en() = practice("demo_attention", original = false, shot = "02_practice_evidence_text_en")

    @Test fun materials_file_original_en() {
        val deps = container()
        val space = awaitIo {
            val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            val session = deps.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_file", mode = "mock", title = "01-attention.pdf")
            deps.tutors.save(TutorCache(session))
            SourceDocumentStore(File(app.filesDir, "source-documents")).save(session.documentId, session.documentVersion, 15, DocumentLayout(List(15) { PageBox(it, 612f, 792f) }), "%PDF-kept".toByteArray())
            session to deps.spaces.list().single { it.members == listOf(SpaceMember.ServerDocumentRef(session.documentId, session.documentVersion)) }
        }
        val (session, s) = space
        val nav = Navigator(SavedStateHandle()).apply { openSpace(s.spaceId, true) }
        rule.setContent {
            A2gTheme { CompositionLocalProvider(LocalPageImages provides { _ -> DrawnPages(15) }) { AppRoot(deps, nav) } }
        }
        wait { has("material_row_${session.documentId}") }
        rule.onNodeWithTag("material_row_${session.documentId}").performClick()
        wait { has("source_pdf_page") }
        rule.waitForIdle()
        captureScreenRoboImage("$out/03_materials_file_original_en.png")
    }

    @Test @Config(qualifiers = "zh-w393dp-h852dp-xxhdpi")
    fun practice_evidence_text_zh() = practice("demo_zh_spaced", original = false, shot = "04_practice_evidence_text_zh")
}
