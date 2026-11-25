package com.anything2game.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.anything2game.app.ui.game.GameBody
import com.anything2game.app.ui.game.GameIntroContent
import com.anything2game.app.ui.game.InstalledGame
import com.anything2game.app.ui.game.PlaygroundContent
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.engine.game.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The words of the Playground and its games are plain: no method names used as labels. Every text the screens render (for all ten
 * games, both rounds, both languages: the shelf, the add sheet, the boards, the results, the Quick look) and every game string resource
 * is checked against the labels the owner rejected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1500dp-xxhdpi")
class JargonGuardTest {
    @get:Rule val rule = createComposeRule()

    private val banned = mapOf(
        "en" to Regex("(?i)information partition|resource allocation|transfer challenge|practice round|teaching setup|split after|left group|right group|still possible|mechanic"),
        "zh" to Regex("信息划分|资源分配|探索关|迁移挑战|仍有可能|左组|右组|教学设定|之后分割|后分割|机制"),
    )

    private fun shownTexts(): List<String> = rule.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root ->
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            listOfNotNull(n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
                n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
                n.config.getOrNull(SemanticsProperties.StateDescription)) + n.children.flatMap(::walk)
        walk(root)
    }

    private fun check(where: String, language: String) {
        val texts = shownTexts()
        assertTrue("$where ($language) rendered no text", texts.count { it.isNotBlank() } >= 3)
        val bad = texts.filter { banned.getValue(language).containsMatchIn(it) }
        assertTrue("$where ($language) shows a rejected label: $bad", bad.isEmpty())
    }

    private fun installed(language: String) = ReviewedGames.all(language).map { InstalledGame(PackCodec.build(it, PackOrigin.PRESET).manifest, it, PackOrigin.PRESET, false, 1) }

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

    private val current = androidx.compose.runtime.mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})
    private var started = false

    /** One composition for the whole test (the rule allows a single setContent); each call swaps what it shows. */
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        if (!started) { started = true; rule.setContent { A2gTheme { Box(Modifier.fillMaxSize()) { current.value() } } } }
        current.value = content
        rule.waitForIdle()
    }

    @Test fun english_screens_use_plain_words() = screens("en")
    @Test fun chinese_screens_use_plain_words() = screens("zh")

    /** The shelf, the add sheet, every board in every state, the results and the Quick look of all ten games. */
    private fun screens(language: String) {
        run {
            RuntimeEnvironment.setQualifiers(if (language == "zh") "+zh" else "+en")
            show { PlaygroundContent(installed(language), null, language, PaddingValues(0.dp), true, {}, {}, {}) }
            check("the shelf and the add sheet", language)
            for (spec in ReviewedGames.all(language)) {
                var run: GameRun = GameRuntime.start(spec, 5)
                val stages = mutableListOf(run)
                run = GameRuntime.apply(solve(run), GameAction.Advance); stages += run
                stages += solve(run)
                // a fresh board, a mid-way board, a cleared round, the second round's new rules, a finished game
                for ((i, r) in stages.withIndex()) {
                    show { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) { GameBody(r, PackOrigin.PRESET, false, 2, {}, {}, onQuickLook = {}) {} } }
                    check("${spec.gameId} board $i", language)
                }
                val help = GameRuntime.apply(GameRuntime.start(spec, 5), GameAction.Help)
                show { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) { GameBody(help, PackOrigin.PRESET, false, 0, {}, {}) {} } }
                check("${spec.gameId} with a hint", language)
                for (i in spec.intro.indices) {
                    show { GameIntroContent(spec, i, {}, false, {}, {}, {}, frozenPhase = 0) }
                    check("${spec.gameId} quick look $i", language)
                }
            }
        }
    }

    @Test fun no_game_string_resource_uses_a_rejected_label() {
        val fields = com.anything2game.app.R.string::class.java.fields.filter { f -> listOf("games_", "gen_", "add_", "playground_", "intro_", "look_", "mechanism_").any { f.name.startsWith(it) } }
        assertTrue(fields.size > 40)
        for (language in listOf("en", "zh")) {
            RuntimeEnvironment.setQualifiers(if (language == "zh") "+zh" else "+en")
            val resources = RuntimeEnvironment.getApplication().resources
            val bad = fields.map { it.name to resources.getText(it.getInt(null)).toString() }.filter { banned.getValue(language).containsMatchIn(it.second) }
            assertTrue("($language) $bad", bad.isEmpty())
        }
    }

    @Test fun the_texts_inside_the_games_themselves_are_plain_too() {
        for (language in listOf("en", "zh")) for (spec in ReviewedGames.all(language)) {
            val texts = listOf(spec.title, spec.teachingSetting, spec.learn.orEmpty()) + spec.stages.map { it.goal } + spec.intro.flatMap { listOf(it.title, it.body) }
            val bad = texts.filter { banned.getValue(language).containsMatchIn(it) }
            assertTrue("${spec.gameId} ($language): $bad", bad.isEmpty())
        }
    }
}
