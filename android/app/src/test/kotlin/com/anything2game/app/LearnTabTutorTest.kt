package com.anything2game.app

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.anything2game.app.ui.home.HomeContent
import com.anything2game.app.ui.home.HomeNavigation
import com.anything2game.app.ui.home.HomeTab
import com.anything2game.app.ui.library.LibraryViewModel
import com.anything2game.app.ui.theme.A2gTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Learn tab offers an explanation for every course whose pack carries a tutor script, straight from the shipped presets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LearnTabTutorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun learn_tab_lists_the_retrieval_example_from_its_pack_and_opens_it() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        var vm: LibraryViewModel? = null
        val opened = mutableListOf<String>()
        rule.setContent {
            A2gTheme {
                val model = androidx.lifecycle.viewmodel.compose.viewModel { LibraryViewModel(deps) }.also { vm = it }
                val state = model.state.collectAsState().value
                Scaffold(bottomBar = { HomeNavigation(HomeTab.LEARN) {} }) { padding ->
                    HomeContent(HomeTab.LEARN, state, "mochi", "", onSelectAvatar = {}, onRename = {}, onPickPdf = {},
                        onRead = { opened += it }, onPlay = { _, _, _ -> }, onSelectTab = {}, onRetry = {}, padding = padding)
                }
            }
        }
        rule.waitUntil(30000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); vm?.state?.value?.loading == false }
        val scripted = vm!!.state.value.entries.filter { it.hasTutor }.map { it.summary.packageId }
        assertTrue("scripted: $scripted", "pkg_demo_retrieval" in scripted)
        rule.onNodeWithTag("home_LEARN").performScrollToNode(hasTestTag("start_explanation_pkg_demo_retrieval"))
        rule.onNodeWithTag("start_explanation_pkg_demo_retrieval").assertHasClickAction().performClick()
        assertEquals(listOf("pkg_demo_retrieval"), opened)
        rule.onNodeWithText("FIXED EXAMPLE / MOCK · NO AI").assertDoesNotExist()
        // Every scripted course gets its own card.
        scripted.forEach { rule.onNodeWithTag("home_LEARN").performScrollToNode(hasTestTag("start_explanation_$it")) }
    }
}
