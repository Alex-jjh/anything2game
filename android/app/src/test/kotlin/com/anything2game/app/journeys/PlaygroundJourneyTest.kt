package com.anything2game.app.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import com.anything2game.app.R
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.game.GameAction
import com.anything2game.contract.game.ReviewedGames
import com.anything2game.contract.game.Zone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The Playground as a student uses it: add a game, slide a cut, drag a token, earn stars. Every route is a real touch on the real app. */
class PlaygroundJourneyTest : JourneyTest() {
    private fun playground() { launch(); chooseGuest(); tap(rule.onNodeWithTag("tab_PLAYGROUND")); waitForTag("home_PLAYGROUND") }

    @Test fun the_playground_shows_every_prepared_game_and_no_server_form() {
        playground()
        waitForTag("add_game")
        for (spec in ReviewedGames.all("en")) waitFor("tile ${spec.gameId}") { textShown(spec.title) || runCatching {
            rule.onNodeWithTag("home_PLAYGROUND").performScrollToNode(hasText(spec.title)); true }.getOrDefault(false) }
        rule.onAllNodesWithTag("concept_input").assertCountEquals(0)
        rule.onAllNodesWithTag("generate_game").assertCountEquals(0)
    }

    @Test fun adding_a_known_topic_plays_the_sequence_then_opens_the_prepared_game_labelled_as_a_demo() {
        playground()
        tap(rule.onNodeWithTag("add_game"))
        waitForTag("add_topic")
        rule.onNodeWithTag("add_generate").assertIsNotEnabled()
        rule.onNodeWithTag("add_topic").performTextInput("git bisect")
        tap(rule.onNodeWithTag("add_generate"))
        waitForTag("game_board")
        waitForText("Hunt the broken commit")
        rule.onNodeWithTag("demo_badge").assertExists()
        rule.onNodeWithText(str(R.string.games_demo_badge)).assertExists()
    }

    @Test fun a_suggestion_chip_fills_the_topic_and_a_chinese_alias_finds_its_game() {
        playground()
        tap(rule.onNodeWithTag("add_game"))
        tap(rule.onNodeWithTag("topic_chip_comparative-advantage"))
        rule.onNodeWithTag("add_topic").assertTextContains("Comparative advantage")
        tap(rule.onNodeWithTag("add_generate"))
        waitForTag("game_board"); waitForText("Wine or cloth?")
        systemBack(); waitForTag("add_game")
        tap(rule.onNodeWithTag("add_game"))
        rule.onNodeWithTag("add_topic").performTextInput("机会成本")
        rule.onNodeWithTag("add_topic").assertTextContains("机会成本")
        tap(rule.onNodeWithTag("add_generate"))
        waitForTag("game_board"); waitForText("Two workshops")
    }

    @Test fun an_unknown_topic_offers_the_prepared_games_to_choose_from() {
        playground()
        tap(rule.onNodeWithTag("add_game"))
        rule.onNodeWithTag("add_topic").performTextInput("orbital dynamics")
        tap(rule.onNodeWithTag("add_generate"))
        waitForTag("chooser")
        waitForText(str(R.string.add_choose))
        rule.onAllNodesWithTag("game_board").assertCountEquals(0)
        tap(rule.onNodeWithTag("chooser_farm-crops-en"))
        waitForTag("game_board"); waitForText("One farm, two crops")
        assertTrue(rule.onAllNodesWithTag("demo_badge").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun the_staged_sequence_takes_two_to_three_seconds_when_animations_are_on() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        playground()
        tap(rule.onNodeWithTag("add_game"))
        rule.onNodeWithTag("add_topic").performTextInput("guess the number")
        val start = rule.mainClock.currentTime
        tap(rule.onNodeWithTag("add_generate"))
        waitForTag("game_board"); waitForText("High or low?")
        val elapsed = rule.mainClock.currentTime - start
        assertTrue("sequence took ${elapsed}ms", elapsed in 2000L..3500L)
    }

    @Test fun sliding_across_the_row_moves_the_cut_and_asking_rules_out_the_other_side() {
        playground()
        waitForTag("game_partition"); tap(rule.onNodeWithTag("game_partition"))
        waitForTag("cut_8")
        val grid = rule.onNodeWithTag("tile_grid")
        val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
        fun centre(n: Int) = rule.onNodeWithTag("cut_$n").fetchSemanticsNode().boundsInRoot.center - origin
        grid.performTouchInput { down(centre(3)); moveTo(centre(3) + Offset(20f, 0f)); moveTo(centre(5)); moveTo(centre(6)); up() }
        waitForText(str(R.string.games_split, 6))
        rule.onNodeWithTag("split_submit").assertIsEnabled()
        tap(rule.onNodeWithTag("split_submit"))
        waitFor("a question was spent") { textShown(str(R.string.games_queries_left, 3, "questions")) }
    }

    @Test fun dragging_a_token_onto_a_zone_places_it_and_names_the_trade_off() {
        playground()
        waitForTag("game_allocation"); tap(rule.onNodeWithTag("game_allocation"))
        waitForTag("token_tray")
        val tray = rule.onNodeWithTag("token_tray")
        val origin = tray.fetchSemanticsNode().boundsInRoot.topLeft
        val zone = rule.onNodeWithTag("place_SECOND").fetchSemanticsNode().boundsInRoot.center - origin
        tray.performTouchInput { down(Offset(20f, 20f)); moveTo(Offset(50f, 40f)); moveTo(Offset(90f, 80f)); moveTo(zone); up() }
        waitForText("5 tokens left")
        rule.onNodeWithTag("cost_line").assertExists()
        val actions = runBlocking { deps.learningProgress!!.checkpoint(ContentVersion("workshop-en", "1"))!!.actions }
        assertEquals(listOf<GameAction>(GameAction.Place(Zone.SECOND)), actions)
        rule.onNodeWithTag("dragged_token").assertDoesNotExist()
    }

    @Test fun a_drag_released_outside_every_zone_places_nothing() {
        playground()
        waitForTag("game_allocation"); tap(rule.onNodeWithTag("game_allocation"))
        waitForTag("token_tray")
        val tray = rule.onNodeWithTag("token_tray")
        tray.performTouchInput { down(Offset(20f, 20f)); moveTo(Offset(60f, 10f)); moveTo(Offset(200f, -40f)); up() }
        rule.waitForIdle()
        rule.onNodeWithText("6 tokens left").assertExists()
        assertNull(runBlocking { deps.learningProgress!!.checkpoint(ContentVersion("workshop-en", "1")) }?.actions?.firstOrNull { it is GameAction.Place })
    }

    @Test fun clearing_both_rounds_shows_the_new_conditions_a_celebration_and_keeps_the_stars() {
        playground()
        waitForTag("game_allocation"); tap(rule.onNodeWithTag("game_allocation"))
        waitForTag("place_FIRST")
        fun place(zone: String, times: Int) = repeat(times) {
            val control = rule.onNodeWithTag("place_$zone")
            waitFor("token controls ready") { runCatching { control.assertIsEnabled(); true }.getOrDefault(false) }
            tap(control)
        }
        place("FIRST", 3); place("SECOND", 3)
        waitForText("0 tokens left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForTag("game_result"); waitForTag("game_stars")
        waitForText(str(R.string.games_won))
        tap(rule.onNodeWithTag("game_advance"))
        waitForTag("new_conditions")
        waitForText(str(R.string.games_new_conditions))
        place("FIRST", 3); place("SECOND", 1)
        waitForText("0 tokens left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForText(str(R.string.games_complete))
        waitForTag("game_stars")
        val version = ContentVersion("workshop-en", "1")
        waitFor("best stars saved") { deps.homePreferences.bestStars(version) == 3 }
        systemBack()
        waitForTag("home_PLAYGROUND")
        waitFor("three stars on the tile") { rule.onAllNodes(hasContentDescription(str(R.string.playground_stars_cd, 3))).fetchSemanticsNodes().size == 1 }
    }
}
