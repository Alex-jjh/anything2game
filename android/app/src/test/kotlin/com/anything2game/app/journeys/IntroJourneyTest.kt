package com.anything2game.app.journeys

import androidx.compose.ui.test.*
import com.anything2game.app.R
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.game.IntroKind
import com.anything2game.contract.game.ReviewedGames
import org.junit.Assert.*
import org.junit.Test

/** The "Quick look" before a game, as a student meets it: it opens by itself once, can be skipped or watched, and can be opened again. */
class IntroJourneyTest : JourneyTest() {
    private val spec = ReviewedGames.partition("en")
    private val version = ContentVersion(spec.gameId, spec.gameVersion)

    private fun playground() { launch(); chooseGuest(); tap(rule.onNodeWithTag("tab_PLAYGROUND")); waitForTag("home_PLAYGROUND"); waitForTag("game_partition") }
    private fun has(tag: String) = exists(hasTestTag(tag))
    private fun title() = rule.onNodeWithTag("intro_title").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")

    @Test fun the_first_open_shows_the_quick_look_skip_goes_to_the_board_and_it_does_not_come_back() {
        playground()
        tap(rule.onNodeWithTag("game_partition"))
        waitForTag("game_intro")
        assertFalse("the board waits behind the story", has("game_board"))
        assertEquals(spec.intro.first().title, title())
        tap(rule.onNodeWithTag("intro_skip"))
        waitForTag("game_board")
        assertFalse(has("game_intro"))
        assertTrue(deps.homePreferences.introSeen(version))
        systemBack(); waitForTag("home_PLAYGROUND"); waitForTag("game_partition")
        tap(rule.onNodeWithTag("game_partition"))
        waitForTag("game_board")
        assertFalse("second visit goes straight to the game", has("game_intro"))
    }

    @Test fun tapping_swiping_and_the_buttons_move_through_the_scenes_and_the_last_one_has_the_source_and_a_big_play_button() {
        playground()
        tap(rule.onNodeWithTag("game_partition")); waitForTag("game_intro")
        tap(rule.onNodeWithTag("intro_next"))
        assertEquals(spec.intro[1].title, title())
        rule.onNodeWithTag("intro_text").assertExists()
        rule.onNodeWithTag("game_intro").performTouchInput { swipeLeft() }
        waitFor("the swipe moved on") { title() == spec.intro[2].title }
        rule.onNodeWithTag("game_intro").performTouchInput { swipeRight() }
        waitFor("the swipe moved back") { title() == spec.intro[1].title }
        tap(rule.onNodeWithTag("intro_prev"))
        assertEquals(spec.intro[0].title, title())
        rule.onNodeWithTag("intro_prev").assertDoesNotExist()
        repeat(spec.intro.lastIndex) { tap(rule.onNodeWithTag("intro_next")) }
        assertEquals(IntroKind.HOW_TO_PLAY, spec.intro.last().kind)
        assertEquals(spec.intro.last().title, title())
        rule.onNodeWithTag("intro_next").assertDoesNotExist()
        rule.onNodeWithTag("intro_skip").assertDoesNotExist()
        for (line in spec.intro.last().body.lines()) assertTrue("how to play line: $line", textShown(line.substringAfter(": ").trim()) || rule.onAllNodes(hasText(line.substringAfter(": ").trim(), substring = true)).fetchSemanticsNodes().isNotEmpty())
        // the source opens the same link sheet the rest of the app uses
        tap(rule.onNodeWithTag("intro_source"))
        waitForText("NIST Dictionary of Algorithms and Data Structures", 5_000)
        tap(rule.onNode(hasText(str(R.string.action_back)) and hasClickAction()))
        tap(rule.onNodeWithTag("intro_play"))
        waitForTag("game_board")
        assertTrue(deps.homePreferences.introSeen(version))
    }

    @Test fun back_steps_to_the_previous_scene_and_leaves_from_the_first() {
        playground()
        tap(rule.onNodeWithTag("game_partition")); waitForTag("game_intro")
        tap(rule.onNodeWithTag("intro_next")); systemBack()
        assertEquals(spec.intro.first().title, title())
        systemBack(); waitForTag("home_PLAYGROUND")
        assertFalse("leaving is not seeing", deps.homePreferences.introSeen(version))
    }

    @Test fun quick_look_on_the_board_opens_it_again_and_close_returns_to_the_same_board() {
        playground()
        openGame(rule.onNodeWithTag("game_partition"))
        tap(rule.onNodeWithTag("cut_8")); tap(rule.onNodeWithTag("split_submit"))
        waitForText(str(R.string.games_eliminated, 8, "lockers"))
        tap(rule.onNodeWithTag("quick_look"))
        waitForTag("game_intro")
        assertEquals(spec.intro.first().title, title())
        tap(rule.onNodeWithTag("intro_skip"))
        waitForTag("game_board")
        waitForText(str(R.string.games_eliminated, 8, "lockers"))
        tap(rule.onNodeWithTag("quick_look")); waitForTag("game_intro"); systemBack()
        waitForTag("game_board")
    }

    @Test fun the_tile_has_a_quick_look_that_plays_into_the_game() {
        playground()
        tap(rule.onNodeWithTag("quick_look_${spec.gameId}"))
        waitForTag("game_intro")
        assertEquals(spec.intro.first().title, title())
        tap(rule.onNodeWithTag("intro_skip"))
        waitForTag("home_PLAYGROUND")
        assertFalse(has("game_intro"))
        tap(rule.onNodeWithTag("quick_look_${spec.gameId}"))
        waitForTag("game_intro")
        repeat(spec.intro.lastIndex) { tap(rule.onNodeWithTag("intro_next")) }
        tap(rule.onNodeWithTag("intro_play"))
        waitForTag("game_board")
        assertFalse(has("game_intro"))
    }

}
