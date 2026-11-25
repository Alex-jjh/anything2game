package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.anything2game.app.ui.game.GameBody
import com.anything2game.app.ui.game.InstalledGame
import com.anything2game.app.ui.game.PlaygroundContent
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.engine.game.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Playground and its games as a student sees them: home, the add sheet and its staged sequence, themed partition and allocation
 * boards mid-play (the allocation ones mid-drag), a cleared round, a completion with stars and the largest font scale.
 * Output: build/outputs/roborazzi/playground/ (copied to docs/design/screens/playground/).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1500dp-xxhdpi")
class PlaygroundScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun spec(key: String, language: String) = ReviewedGames.catalog.first { it.key == key }.spec(language)
    private fun installed(language: String) = ReviewedGames.all(language).mapIndexed { i, s ->
        InstalledGame(PackCodec.build(s, PackOrigin.PRESET).manifest, s, PackOrigin.PRESET, resume = i == 2, stars = if (i == 0) 3 else if (i == 1) 2 else 0)
    }

    private fun host(scale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
            A2gTheme { Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { content() } }
        }
    }

    private fun game(initial: GameRun, scale: Float = 1f, best: Int = 0, backTo: String? = null): MutableState<GameRun> {
        val state = mutableStateOf(initial)
        host(scale) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 24.dp)) {
                GameBody(state.value, PackOrigin.PRESET, false, best, onMove = { state.value = GameRuntime.apply(state.value, it) }, onRestart = {}, backTo = backTo, onQuickLook = {}) {}
            }
        }
        return state
    }

    /** Taps a tile near the middle of the surviving range, as a student choosing a cut. */
    private fun pickCut(state: MutableState<GameRun>, shift: Int = 0) {
        val b = state.value.board as PartitionBoard
        rule.onNodeWithTag("cut_${((b.low + b.high) / 2 + shift).coerceIn(b.low, b.high - 1)}").performClick()
    }

    private fun snap(name: String) { rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/playground/$name.png") }

    private fun apply(run: GameRun, vararg actions: GameAction) = actions.fold(run) { r, a -> GameRuntime.apply(r, a) }

    /** Plays the current stage the way a careful student would. */
    private fun solve(run: GameRun): GameRun {
        var r = run
        when (val c = r.stage.config) {
            is PartitionConfig -> while (r.status == StageStatus.PLAYING) { val b = r.board as PartitionBoard; r = GameRuntime.apply(r, GameAction.Split((b.low + b.high) / 2)) }
            is AllocationConfig -> {
                val first = (c.firstGoal + c.firstYield - 1) / c.firstYield
                repeat(first) { r = GameRuntime.apply(r, GameAction.Place(Zone.FIRST)) }
                repeat(c.tokens - first) { r = GameRuntime.apply(r, GameAction.Place(Zone.SECOND)) }
                r = GameRuntime.apply(r, GameAction.Submit)
            }
        }
        return r
    }

    @Test fun p01_home_en() { host { PlaygroundContent(installed("en"), null, "en", PaddingValues(0.dp), false, {}, {}, {}) }; snap("p01_home_en") }

    @Test fun p02_home_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        host { PlaygroundContent(installed("zh"), null, "zh", PaddingValues(0.dp), false, {}, {}, {}) }; snap("p02_home_zh")
    }

    @Test fun p03_add_sheet_en() { host { PlaygroundContent(installed("en"), null, "en", PaddingValues(0.dp), true, {}, {}, {}) }; snap("p03_add_sheet_en") }

    @Test fun p04_generating_midway_en() {
        host { PlaygroundContent(installed("en"), null, "en", PaddingValues(0.dp), true, {}, {}, {}, previewStep = 2, previewTopic = "git bisect") }
        snap("p04_generating_en")
    }

    @Test fun p05_generating_midway_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        host { PlaygroundContent(installed("zh"), null, "zh", PaddingValues(0.dp), true, {}, {}, {}, previewStep = 1, previewTopic = "猜数字") }
        snap("p05_generating_zh")
    }

    @Test fun p06_partition_git_bisect_midplay_en() {
        val g = game(apply(GameRuntime.start(spec("git-bisect", "en"), 11), GameAction.Split(16), GameAction.Split(8)))
        pickCut(g, 1)
        snap("p06_partition_git_bisect_en")
    }

    @Test fun p07_partition_dictionary_midplay_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        val g = game(apply(GameRuntime.start(spec("dictionary", "zh"), 4), GameAction.Split(12)))
        pickCut(g, -1)
        snap("p07_partition_dictionary_zh")
    }

    @Test fun p08_partition_number_and_layers_before_first_cut_en() {
        game(GameRuntime.start(spec("number-guess", "en"), 3)); snap("p08_partition_number_en")
    }

    @Test fun p09_partition_layers_midplay_en() {
        val g = game(apply(GameRuntime.start(spec("layer-fault", "en"), 9), GameAction.Split(8)))
        pickCut(g)
        snap("p09_partition_layers_en")
    }

    private fun dragFirstChipOver(zone: String) {
        val tray = rule.onNodeWithTag("token_tray")
        val t = tray.fetchSemanticsNode().boundsInRoot
        val z = rule.onNodeWithTag(zone).fetchSemanticsNode().boundsInRoot
        tray.performTouchInput {
            down(Offset(30f, 30f))
            moveTo(Offset(60f, 40f)); moveTo(Offset(90f, 60f))
            moveTo(z.center - t.topLeft)
        }
        rule.waitForIdle()
    }

    @Test fun p10_allocation_wine_mid_drag_en() {
        game(apply(GameRuntime.start(spec("comparative-advantage", "en"), 2), GameAction.Place(Zone.FIRST), GameAction.Place(Zone.SECOND)))
        dragFirstChipOver("place_FIRST")
        snap("p10_allocation_wine_drag_en")
    }

    @Test fun p11_allocation_gpu_mid_drag_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        game(apply(GameRuntime.start(spec("gpu-budget", "zh"), 3), GameAction.Place(Zone.FIRST), GameAction.Place(Zone.FIRST), GameAction.Place(Zone.SECOND)))
        dragFirstChipOver("place_SECOND")
        snap("p11_allocation_gpu_drag_zh")
    }

    @Test fun p12_allocation_farm_and_exam_en() {
        game(apply(GameRuntime.start(spec("farm-crops", "en"), 5), GameAction.Place(Zone.FIRST), GameAction.Place(Zone.FIRST)))
        snap("p12_allocation_farm_en")
    }

    @Test fun p13_stage_cleared_with_stars_en() {
        game(solve(GameRuntime.start(spec("git-bisect", "en"), 7)), best = 3); snap("p13_stage_cleared_en")
    }

    @Test fun p14_new_conditions_en() {
        val cleared = solve(GameRuntime.start(spec("comparative-advantage", "en"), 7))
        game(GameRuntime.apply(cleared, GameAction.Advance)); snap("p14_new_conditions_en")
    }

    @Test fun p15_completed_with_three_stars_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        var r = solve(GameRuntime.start(spec("exam-revision", "zh"), 7))
        r = solve(GameRuntime.apply(r, GameAction.Advance))
        game(r, best = 3); snap("p15_completed_zh")
    }

    @Test fun p20_completed_from_a_route_step_names_the_way_back_en() {
        var r = solve(GameRuntime.start(spec("layer-fault", "en"), 7))
        r = solve(GameRuntime.apply(r, GameAction.Advance))
        game(r, best = 3, backTo = "Deep Residual Learning (ResNet)"); snap("p20_completed_from_route_en")
    }

    @Test fun p16_lost_round_en() {
        var r = GameRuntime.start(spec("farm-crops", "en"), 5)
        repeat(5) { r = GameRuntime.apply(r, GameAction.Place(Zone.FIRST)) }
        repeat(4) { r = GameRuntime.apply(r, GameAction.Place(Zone.SECOND)) }
        game(GameRuntime.apply(r, GameAction.Submit)); snap("p16_lost_en")
    }

    @Test fun p17_font_scale_two_partition_en() {
        val g = game(apply(GameRuntime.start(spec("git-bisect", "en"), 11), GameAction.Split(16)), scale = 2f)
        pickCut(g); snap("p17_font2_partition_en")
    }

    @Test fun p18_font_scale_two_allocation_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        game(apply(GameRuntime.start(spec("gpu-budget", "zh"), 3), GameAction.Place(Zone.FIRST)), scale = 2f); snap("p18_font2_allocation_zh")
    }

    @Test fun p19_font_scale_two_home_en() {
        host(2f) { PlaygroundContent(installed("en"), null, "en", PaddingValues(0.dp), false, {}, {}, {}) }; snap("p19_font2_home_en")
    }
}
