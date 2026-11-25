package com.anything2game.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.anything2game.app.ui.game.GameIntroContent
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.game.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Quick look as a player sees it: scenes of three games in both languages, the how-to-play scene, large text and landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class IntroScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun spec(key: String, language: String) = ReviewedGames.catalog.first { it.key == key }.spec(language)

    private class Shot(val spec: GameSpec, val index: Int, val scale: Float, val phase: Int?)
    private val shot = androidx.compose.runtime.mutableStateOf<Shot?>(null)
    private var started = false

    /** One composition per test (the rule allows a single setContent); each call swaps the scene being shown. */
    private fun scene(spec: GameSpec, index: Int, name: String, scale: Float = 1f, phase: Int? = null) {
        if (!started) {
            started = true
            rule.setContent {
                val d = LocalDensity.current
                val s = shot.value
                if (s != null) CompositionLocalProvider(LocalDensity provides Density(d.density, s.scale)) {
                    A2gTheme { Box(Modifier.fillMaxSize()) { androidx.compose.runtime.key(s.index, s.spec.gameId, s.scale) {
                        GameIntroContent(s.spec, s.index, {}, false, {}, {}, {}, frozenPhase = s.phase) } } }
                }
            }
        }
        shot.value = Shot(spec, index, scale, phase)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/playground/$name.png")
    }

    private fun kind(spec: GameSpec, kind: IntroKind) = spec.intro.indexOfFirst { it.kind == kind }

    @Test fun dictionary_en() {
        val s = spec("dictionary", "en")
        scene(s, kind(s, IntroKind.HOOK), "intro_dictionary_1_hook_en")
        scene(s, kind(s, IntroKind.STORY), "intro_dictionary_2_story_en")
        scene(s, kind(s, IntroKind.IDEA), "intro_dictionary_3_idea_en")
        scene(s, kind(s, IntroKind.HOW_IT_WORKS), "intro_dictionary_4_how_en", phase = 4)
        scene(s, kind(s, IntroKind.HOW_TO_PLAY), "intro_dictionary_5_play_en")
    }

    @Test fun dictionary_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        val s = spec("dictionary", "zh")
        scene(s, kind(s, IntroKind.HOOK), "intro_dictionary_1_hook_zh")
        scene(s, kind(s, IntroKind.HOW_IT_WORKS), "intro_dictionary_4_how_zh", phase = 2)
        scene(s, kind(s, IntroKind.HOW_TO_PLAY), "intro_dictionary_5_play_zh")
    }

    @Test fun workshop_en() {
        val s = spec("workshop", "en")
        scene(s, kind(s, IntroKind.HOOK), "intro_workshop_1_hook_en")
        scene(s, kind(s, IntroKind.STORY), "intro_workshop_2_story_en")
        scene(s, kind(s, IntroKind.HOW_IT_WORKS), "intro_workshop_4_how_en", phase = 1)
        scene(s, kind(s, IntroKind.HOW_TO_PLAY), "intro_workshop_5_play_en")
    }

    @Test fun workshop_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        val s = spec("workshop", "zh")
        scene(s, kind(s, IntroKind.HOOK), "intro_workshop_1_hook_zh")
        scene(s, kind(s, IntroKind.HOW_IT_WORKS), "intro_workshop_4_how_zh", phase = 0)
        scene(s, kind(s, IntroKind.HOW_TO_PLAY), "intro_workshop_5_play_zh")
    }

    @Test fun git_bisect_en() {
        val s = spec("git-bisect", "en")
        scene(s, kind(s, IntroKind.STORY), "intro_git_bisect_2_story_en")
        scene(s, kind(s, IntroKind.IDEA), "intro_git_bisect_3_idea_en")
        scene(s, kind(s, IntroKind.HOW_IT_WORKS), "intro_git_bisect_4_how_en", phase = 2)
    }

    @Test fun git_bisect_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        val s = spec("git-bisect", "zh")
        scene(s, kind(s, IntroKind.HOOK), "intro_git_bisect_1_hook_zh")
        scene(s, kind(s, IntroKind.STORY), "intro_git_bisect_2_story_zh")
        scene(s, kind(s, IntroKind.IDEA), "intro_git_bisect_3_idea_zh")
        scene(s, kind(s, IntroKind.HOW_TO_PLAY), "intro_git_bisect_5_play_zh")
    }

    @Test fun large_text_how_to_play_en() {
        val s = spec("farm-crops", "en")
        scene(s, kind(s, IntroKind.HOW_TO_PLAY), "intro_font2_play_en", scale = 2f)
    }

    @Test @Config(qualifiers = "w852dp-h393dp-xxhdpi")
    fun landscape_how_it_works_en() {
        val s = spec("number-guess", "en")
        scene(s, kind(s, IntroKind.HOW_IT_WORKS), "intro_landscape_how_en", phase = 3)
    }
}
