package com.anything2game.app

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.anything2game.app.ui.home.*
import com.anything2game.app.ui.library.LibraryUiState
import com.anything2game.app.ui.theme.A2gTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeFlowTest {
    @get:Rule val rule = createComposeRule()
    private var tab by mutableStateOf(HomeTab.LEARN)
    private var nickname by mutableStateOf("")
    private var avatar by mutableStateOf("mochi")
    private var picks = 0
    private var library by mutableStateOf(LibraryUiState(false))
    private var played: String? = null
    private var reads = 0
    @Before fun setup() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rule.setContent {
            A2gTheme {
                Scaffold(bottomBar = { HomeNavigation(tab) { tab = it } }) { padding ->
                    HomeContent(tab, library, avatar, nickname,
                        onSelectAvatar = { avatar = it }, onRename = { nickname = it }, onPickPdf = { picks++ },
                        onRead = { reads++ }, onPlay = { id, _, _ -> played = id }, onSelectTab = { tab = it }, onRetry = {}, padding = padding)
                }
            }
        }
    }
    @Test fun three_tabs_have_selected_state_and_center_upload_is_reachable() {
        rule.onNodeWithTag("tab_LEARN").assertIsSelected()
        rule.onNodeWithTag("pick_pdf").performClick()
        assertEquals(1, picks)
        rule.onNodeWithTag("tab_PRACTICE").performClick()
        rule.onNodeWithTag("tab_PRACTICE").assertIsSelected()
        rule.onNodeWithTag("home_PRACTICE").assertExists()
        rule.onNodeWithTag("tab_PROFILE").performClick()
        rule.onNodeWithText("My learning file").assertIsDisplayed()
        rule.onNodeWithTag("tab_PROFILE").assertIsSelected()
    }
    // Native nickname editing (save / cancel / relaunch) is covered by
    // android/qa/home_navigation_smoke.py on an emulator. Robolectric 4.18 does not
    // reliably idle the secondary dialog window with this Compose version.
    @Test fun profile_shortcut_navigates_to_practice() {
        rule.onNodeWithTag("tab_PROFILE").performClick()
        rule.onNodeWithText("Go to practice").performScrollTo().performClick()
        rule.onNodeWithTag("tab_PRACTICE").assertIsSelected()
    }
    @Test fun new_practice_does_not_require_the_old_self_rating() {
        library = LibraryUiState(false, listOf(com.anything2game.app.ui.library.LibraryEntry(
            com.anything2game.engine.ports.PackageSummary("practice", "A document", "en", 1, true),
            listOf(com.anything2game.app.ui.library.LevelEntry("L1", 1, "First level", com.anything2game.app.ui.library.LevelStatus.New)), prepared = false)))
        rule.onNodeWithTag("tab_PRACTICE").performClick()
        rule.onNodeWithText("Start practice").performScrollTo().performClick()
        assertEquals("practice", played)
        assertEquals(0, reads)
    }

    private fun entry(id: String, title: String, tutor: Boolean, progress: com.anything2game.app.data.tutor.TutorProgress? = null) = com.anything2game.app.ui.library.LibraryEntry(
        com.anything2game.engine.ports.PackageSummary(id, title, "en", 1, true),
        listOf(com.anything2game.app.ui.library.LevelEntry("L1", 1, "First level", com.anything2game.app.ui.library.LevelStatus.New)), hasTutor = tutor, tutorProgress = progress)

    @Test fun learn_lists_only_explained_courses_and_uploaded_documents_with_no_dead_end() {
        val upload = com.anything2game.contract.tutor.TutorSession(sessionId = "tut_up", documentId = "doc_up", documentVersion = "v1", title = "My uploaded paper", language = "en", mode = "mock",
            questions = emptyList(), sources = emptyList())
        library = LibraryUiState(false, listOf(entry("explained", "Explained course", true), entry("practice_only", "Practice-only course", false)), tutorials = listOf(upload))
        rule.onNodeWithText("Explained course").assertExists()
        rule.onNodeWithTag("home_LEARN").performScrollToNode(hasText("My uploaded paper"))
        rule.onNodeWithText("My uploaded paper").assertExists()
        rule.onNodeWithText("Practice-only course").assertDoesNotExist()           // it has no explanation: it lives in Practice
        rule.onNodeWithText("View material").assertDoesNotExist()
        rule.onNodeWithText("Other local materials").assertDoesNotExist()
        rule.onNodeWithTag("tab_PRACTICE").performClick()
        rule.onNodeWithTag("home_PRACTICE").performScrollToNode(hasText("Practice-only course"))
        rule.onNodeWithText("Practice-only course").assertExists()
    }

    @Test fun learn_with_nothing_to_explain_says_so_instead_of_listing_practice_courses() {
        library = LibraryUiState(false, listOf(entry("practice_only", "Practice-only course", false)))
        rule.onNodeWithText("Practice-only course").assertDoesNotExist()
        rule.onNodeWithTag("pick_pdf").assertExists()
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(com.anything2game.app.R.string.empty_library)).assertExists()
    }

    @Test fun the_learn_card_says_start_continue_with_the_concept_or_explained() {
        fun p(explaining: Boolean, at: Int) = com.anything2game.app.data.tutor.TutorProgress(explaining, at, 4)
        val app = RuntimeEnvironment.getApplication()
        library = LibraryUiState(false, listOf(
            entry("fresh", "Fresh", true), entry("diag", "Diag", true, p(false, 0)),
            entry("mid", "Mid", true, p(true, 1)), entry("first", "First", true, p(true, 0)), entry("done", "Done", true, p(true, 3))))
        fun label(id: String) = rule.onNodeWithTag("home_LEARN").performScrollToNode(hasTestTag("start_explanation_$id")).let {
            rule.onNodeWithTag("start_explanation_$id").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { t -> t.text }
        }
        assertEquals(app.getString(com.anything2game.app.R.string.tutor_start_explanation), label("fresh"))
        assertEquals(app.getString(com.anything2game.app.R.string.tutor_card_continue), label("diag"))
        assertEquals(app.getString(com.anything2game.app.R.string.tutor_card_continue_concept, 2, 4), label("mid"))
        assertEquals("Continue · concept 2/4", label("mid"))
        assertEquals(app.getString(com.anything2game.app.R.string.tutor_card_continue_concept, 1, 4), label("first"))
        assertEquals(app.getString(com.anything2game.app.R.string.tutor_card_explained), label("done"))
    }
}
