package com.anything2game.app.journeys

import androidx.compose.ui.test.*
import com.anything2game.app.R
import com.anything2game.app.data.mutex
import com.anything2game.app.ui.Navigator
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class ConceptSpacesJourneyTest : JourneyTest() {
    @Test fun reading_and_practice_preferences_land_on_spaces() {
        for (old in listOf("LEARN", "PRACTICE")) {
            val nav = Navigator(SavedStateHandle())
            nav.initializeTab(old)
            assertEquals("SPACES", nav.tab.value.name)
        }
    }

    @Test fun partition_move_survives_recreation_and_reopening() {
        launch()
        waitForTag("choose_guest")
        tap(rule.onNodeWithTag("choose_guest"))
        waitFor("Spaces home", 5000) { exists(hasTestTag("home_SPACES")) }
        tap(rule.onNodeWithTag("tab_PLAYGROUND"))
        waitForTag("game_partition")
        openGame(rule.onNodeWithTag("game_partition"))
        waitForTag("split_submit")
        rule.onNodeWithTag("split_submit").assertIsNotEnabled()
        tap(rule.onNodeWithTag("cut_8"))
        scenario!!.recreate()
        // Hold the real durable-store lock to exercise the interval between a tap and its committed checkpoint.
        val ledger = java.io.File(app.filesDir, "learning.json").mutex()
        com.anything2game.app.awaitIo { ledger.lock() }
        try {
            tap(rule.onNodeWithTag("split_submit"))
            rule.onNode(hasText(str(R.string.action_back)) and hasClickAction()).assertIsNotEnabled()
            systemBack()
            rule.onNodeWithTag("game_board").assertExists()
        } finally { ledger.unlock() }
        waitForText("Crossed off 8 lockers")
        scenario!!.recreate()
        waitForText("Crossed off 8 lockers")
        systemBack()
        waitForTag("game_partition")
        openGame(rule.onNodeWithTag("game_partition"))
        waitForText("Crossed off 8 lockers")
    }

    @Test fun workshop_allocation_undo_and_changed_transfer_update_the_learning_record_only_after_winning() {
        launch(); chooseGuest()
        tap(rule.onNodeWithTag("tab_PLAYGROUND"))
        waitForTag("game_allocation")
        openGame(rule.onNodeWithTag("game_allocation"))
        waitForTag("place_FIRST")
        fun place(zone: String, times: Int) = repeat(times) {
            val control = rule.onNodeWithTag("place_$zone")
            waitFor("token controls ready") { runCatching { control.assertIsEnabled(); true }.getOrDefault(false) }
            tap(control)
        }
        place("SECOND", 1)
        waitForText("5 helpers left")
        tapText(str(R.string.games_undo))
        waitForText("6 helpers left")
        place("FIRST", 3); place("SECOND", 3)
        waitForText("0 helpers left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForTag("game_advance")
        assertTrue(com.anything2game.app.awaitIo { deps.learningProgress!!.projection().days.isEmpty() })
        tap(rule.onNodeWithTag("game_advance"))
        waitForText(str(R.string.games_transfer))
        waitForText("4 helpers left")
        place("FIRST", 3); place("SECOND", 1)
        waitForText("0 helpers left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForText(str(R.string.games_complete))
        systemBack(); openMe()
        scrollMeTo(completedDays(1, 1))
        assertTrue(textShown(completedDays(1, 1)))
    }

    @Test fun created_space_can_group_and_remove_a_source_without_losing_its_original_space() {
        launch(); chooseGuest()
        waitFor("create available") { runCatching { rule.onNodeWithTag("space_create").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("space_create"))
        rule.onNodeWithTag("space_name").performTextInput("My two papers")
        tap(rule.onNodeWithTag("save_space"))
        waitForTag("route_empty")                       // a new Space opens on its (empty) route; materials are one tab away
        tap(rule.onNodeWithTag("show_materials"))
        waitForTag("add_existing")
        for (pkg in listOf("demo_attention", "demo_resnet")) {
            waitFor("ready for another attachment") { runCatching { rule.onNodeWithTag("add_existing").assertIsEnabled(); true }.getOrDefault(false) }
            tap(rule.onNodeWithTag("add_existing"))
            val title = Level(pkg, com.anything2game.contract.FrameId.CLASSIC).pkg.title
            tap(rule.onNode(hasText(title) and hasClickAction()))
            waitFor("membership saved") { com.anything2game.app.awaitIo { deps.spaces!!.list().first { it.title == "My two papers" }.members.any { it.version().contentId == pkg } } }
        }
        scenario!!.recreate()
        waitForTag("space_materials")
        tap(rule.onNodeWithTag("show_route"))
        waitForText(str(R.string.route_progress_done, 0, com.anything2game.app.awaitIo {
            val space = deps.spaces!!.list().first { it.title == "My two papers" }
            deps.spaces!!.route(space.spaceId, "en")!!.nodes.size
        }))
        tap(rule.onNodeWithTag("show_materials"))
        rule.onNodeWithTag("space_materials").performScrollToNode(hasText(str(R.string.spaces_remove)))
        val remove = rule.onAllNodes(hasText(str(R.string.spaces_remove)) and hasClickAction())[0]
        waitFor("remove available") { runCatching { remove.assertIsEnabled(); true }.getOrDefault(false) }
        tap(remove)
        waitFor("one remaining member") { com.anything2game.app.awaitIo { deps.spaces!!.list().first { it.title == "My two papers" }.members.size == 1 } }
        systemBack()
        openMaterial(Level("demo_attention", com.anything2game.contract.FrameId.CLASSIC).pkg.title)
        rule.onNodeWithTag("space_materials").performScrollToNode(hasTestTag("start_explanation_demo_attention"))
        rule.onNodeWithTag("start_explanation_demo_attention").assertHasClickAction()
    }
}
