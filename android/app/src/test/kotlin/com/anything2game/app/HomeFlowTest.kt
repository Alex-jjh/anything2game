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

}
