package com.anything2game.app

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.theme.A2gTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpacePdfPickerTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test fun two_selected_files_wait_for_confirmation_and_report_their_own_errors() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        val id = runBlocking { deps.profiles.choose(LocalProfile.GUEST); deps.spaces.create("Imports").spaceId }
        val nav = Navigator(SavedStateHandle()).apply { openSpace(id, true) }
        val uris = listOf("first.pdf", "second.pdf").map { Uri.fromFile(files.newFile(it).apply { writeText("Not a PDF") }) }
        val picker = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, uris)
                }
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { A2gTheme { AppRoot(deps, nav) } }
        }
        fun ready(tag: String) = rule.waitUntil(20000) {
            ShadowLooper.idleMainLooper()
            runCatching { rule.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false)
        }
        ready("pick_pdfs")
        rule.onNodeWithTag("pick_pdfs").performClick()
        ready("confirm_pdfs")
        assertTrue(runBlocking { deps.pdfImports.list().isEmpty() })
        rule.onNodeWithText("first.pdf").assertExists()
        rule.onNodeWithText("second.pdf").assertExists()
        rule.onNodeWithTag("confirm_pdfs").performClick()
        rule.waitUntil(20000) {
            ShadowLooper.idleMainLooper()
            runBlocking { deps.pdfImports.list().count { it.error != null } == 2 }
        }
        val rows = runBlocking { deps.pdfImports.list() }
        assertEquals(setOf("first.pdf", "second.pdf"), rows.map { it.title }.toSet())
        assertTrue(rows.all { it.spaceId == id && it.sessionId == null })
        for (row in rows) {
            rule.waitUntil(20000) {
                ShadowLooper.idleMainLooper()
                runCatching { rule.onNodeWithTag("space_materials").performScrollToNode(hasText(row.title)); true }.getOrDefault(false)
            }
            rule.onNodeWithText(row.title).assertExists()
            rule.onAllNodesWithText(row.error!!, substring = true).assertCountEquals(0)
            assertTrue(rule.onAllNodes(hasText("PDF", substring = true) and hasText("choose", substring = true, ignoreCase = true))
                .fetchSemanticsNodes().isNotEmpty())
        }
        assertTrue(runBlocking { deps.spaces.list().first { it.spaceId == id }.members.isEmpty() })
    }

    @Test fun ambiguous_upload_needs_a_separate_confirmation_before_another_copy_can_be_sent() {
        val base = AndroidAppContainer(RuntimeEnvironment.getApplication())
        val id = runBlocking { base.profiles.choose(LocalProfile.GUEST); base.spaces.create("One PDF").spaceId }
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val tutor = object : com.anything2game.app.data.tutor.TutorRepository by base.tutors {
            override suspend fun upload(request: com.anything2game.app.importing.ImportRequest): com.anything2game.contract.DocumentResponse {
                attempts.incrementAndGet()
                throw java.io.IOException("response lost")
            }
        }
        val queue = com.anything2game.app.data.learning.PdfImportQueue(files.newFolder(), { "%PDF-first".toByteArray() }, tutor, base.spaces)
        val row = runBlocking {
            val request = com.anything2game.app.importing.ImportRequest.PdfFile("unknown.pdf", Uri.parse("content://pdf/unknown"), null)
            queue.process(queue.enqueue(id, listOf(request)).single().id)
        }
        assertEquals(com.anything2game.app.data.learning.PdfImportQueue.UPLOAD_OUTCOME_UNKNOWN, row.error)
        val deps = object : AppDependencies by base { override val pdfImports = queue }
        val nav = Navigator(SavedStateHandle()).apply { openSpace(id, true) }
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        val again = RuntimeEnvironment.getApplication().getString(R.string.spaces_upload_again)
        rule.waitUntil(20000) {
            ShadowLooper.idleMainLooper()
            runCatching { rule.onNodeWithTag("space_materials").performScrollToNode(hasText(again)); true }.getOrDefault(false)
        }
        rule.onNodeWithText(again).performClick()
        rule.onNode(isDialog()).assertExists()
        assertEquals(1, attempts.get())
        rule.onNode(hasText(again) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(20000) { ShadowLooper.idleMainLooper(); attempts.get() == 2 }
        assertEquals(row.id, runBlocking { queue.list().single().id })
    }
}
