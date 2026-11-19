package com.anything2game.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.anything2game.app.data.Familiarity
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.FrameId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
class ReadingFlowTest {
    @get:Rule val rule = createComposeRule()
    @Test fun preparation_reading_sources_and_practice_use_existing_package() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        val pkg = runBlocking { deps.packages.load("demo_attention").getOrThrow() }
        var played: String? = null
        rule.setContent {
            A2gTheme {
                ReadingContent(deps, pkg.packageId, onBack = {}, onPractice = { id, level, frame ->
                    played = "$id:$level"; assertEquals(FrameId.CLASSIC, frame)
                })
            }
        }
        rule.waitUntil(10000) { rule.onAllNodesWithText("New to me").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("New to me")[0].performScrollTo().performClick()
        val level = pkg.levels.first()
        assertEquals(Familiarity.NEW, deps.homePreferences.familiarity(pkg.packageId, pkg.contentVersion, level.levelId))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Read the explanation"))
        rule.onNodeWithText("Read the explanation").performClick()
        assertTrue(deps.homePreferences.isPrepared(pkg.packageId, pkg.contentVersion))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("View source passages"))
        rule.onAllNodesWithText("View source passages")[0].performScrollTo().performClick()
        val firstSource = pkg.chunk(level.units.flatMap { it.item.allSourceRefs }.first().chunkId)!!
        rule.onNodeWithText(firstSource.text).assertExists()
        rule.onAllNodesWithText("Hide source passages")[0].performScrollTo().performClick()
        rule.onAllNodesWithText("Start practice")[0].performScrollTo().performClick()
        assertEquals("${pkg.packageId}:${level.levelId}", played)
    }
}
