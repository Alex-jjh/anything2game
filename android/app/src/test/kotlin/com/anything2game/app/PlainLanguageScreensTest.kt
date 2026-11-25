package com.anything2game.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import com.anything2game.app.data.api.ApiException
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.journeys.PlainLanguageGuardTest
import com.anything2game.app.ui.common.problemCode
import com.anything2game.app.ui.common.problemRes
import com.anything2game.app.ui.importing.ImportContent
import com.anything2game.app.ui.importing.ImportUiState
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Companion of [PlainLanguageGuardTest]: the import screen in each of its states, and every error the app can show.
 * An error is always a plain sentence; a raw code, an exception name or the server's own message never reaches the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1500dp-xxhdpi")
class PlainLanguageScreensTest {
    @get:Rule val rule = createComposeRule()
    private val current = androidx.compose.runtime.mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})
    private var started = false
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        if (!started) { started = true; rule.setContent { A2gTheme { Box(Modifier.fillMaxSize()) { current.value() } } } }
        current.value = content
        rule.waitForIdle()
    }
    private fun shown(): List<String> = rule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root ->
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            listOfNotNull(n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }, n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")) + n.children.flatMap(::walk)
        walk(root)
    }
    private val codes = listOf(
        ErrorCodes.FILE_TOO_LARGE, ErrorCodes.UNSUPPORTED_TYPE, ErrorCodes.NO_TEXT_LAYER, ErrorCodes.ENCRYPTED_PDF, ErrorCodes.NOT_FOUND, ErrorCodes.QUOTA_EXCEEDED,
        ErrorCodes.MODEL_TIMEOUT, ErrorCodes.GENERATION_FAILED, ErrorCodes.BAD_REQUEST, ErrorCodes.UNAUTHORIZED, ErrorCodes.PACKAGE_NOT_READY, ErrorCodes.INTERNAL_ERROR,
        ErrorCodes.PDF_TIMEOUT, "INVALID_PDF", "FILE_UNAVAILABLE", "CONNECTION_OR_FILE_ERROR", "CONNECTION_OR_STORAGE", "INSTALL_UNAVAILABLE", "NOT_CONFIGURED",
        "RESPONSE_TOO_LARGE", "SPACE_STALE", "REQUEST_CONFLICT", "DOCUMENT_VERSION_CHANGED", "SOURCE_CHANGED", "SOURCE_REMOVED", "UPLOAD_OUTCOME_UNKNOWN",
        "TUTOR_STALE", "TUTOR_MODE_CHANGED", "TUTOR_CANCELLED", "TUTOR_ALREADY_ASSESSED", "CANCEL_UNCONFIRMED", "INVALID_OR_UNREADABLE_DATA",
        "NO_AVAILABLE_ACTIVITIES", "INVALID_ACTIVITY", "HTTP_503", "UNKNOWN", "SOMETHING_NEW",
    )

    @Test fun every_error_is_a_plain_sentence_in_both_languages() {
        for (language in listOf("en", "zh")) {
            RuntimeEnvironment.setQualifiers(if (language == "zh") "+zh" else "+en")
            val sentences = codes.map { RuntimeEnvironment.getApplication().getString(problemRes(it)) }
            assertTrue("($language) $sentences", PlainLanguageGuardTest.offenders(sentences, language).isEmpty())
            assertTrue("every sentence ends like a sentence", sentences.all { it.last() in ".。" })
        }
        assertEquals("CONNECTION_OR_FILE_ERROR", problemCode(java.io.IOException("boom")))
        assertEquals("FILE_TOO_LARGE", problemCode(ApiException("FILE_TOO_LARGE", false, "File is 31 MB")))
        assertEquals("UNKNOWN", problemCode(IllegalStateException("java.lang.Boom at line 3")))
    }

    @Test fun the_english_import_screen_is_plain_in_every_state() = importScreen("en")
    @Test fun the_chinese_import_screen_is_plain_in_every_state() = importScreen("zh")

    private fun importScreen(language: String) {
        run {
            RuntimeEnvironment.setQualifiers(if (language == "zh") "+zh" else "+en")
            val text = ImportRequest.SharedText("Lecture 7", "Context-aware systems adapt to the user. ".repeat(6))
            val pdf = ImportRequest.PdfFile("paper.pdf", android.net.Uri.parse("content://x/y.pdf"), 2_000_000L)
            val states = listOf(
                ImportUiState(), ImportUiState(busy = true), ImportUiState(busy = true, uploaded = true),
            ) + codes.map { ImportUiState(error = it) }
            for ((i, state) in states.withIndex()) {
                show { ImportContent(if (i % 2 == 0) text else pdf, onCancel = {}, endpoint = if (i == 0) "" else "https://example.test", state = state, onDemo = {}) }
                val bad = PlainLanguageGuardTest.offenders(shown().filter { it != "https://example.test" }, language)
                assertTrue("import state $i ($language): $bad", bad.isEmpty())
            }
        }
    }
}
