package com.anything2game.app.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import com.anything2game.app.R
import com.anything2game.app.awaitIo
import com.anything2game.contract.FrameId
import com.anything2game.contract.game.AllocationConfig
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.game.GameSpec
import com.anything2game.contract.game.PartitionConfig
import com.anything2game.contract.game.ReviewedGames
import com.anything2game.contract.progress.ActivityOutcome
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.space.PathNode
import com.anything2game.contract.space.SpaceMember
import com.anything2game.contract.tutor.TutorRules
import com.anything2game.contract.tutor.Understanding
import com.anything2game.contract.tutor.UNKNOWN_ANSWER
import com.anything2game.engine.game.GameRuntime
import com.anything2game.engine.game.PartitionBoard
import com.anything2game.engine.game.StageStatus
import com.anything2game.engine.session.Phase
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * QA of the demo path, written the way a first-time student and a sceptical lecturer would walk it: the real app, real taps.
 * Journeys 1-8 of the demo-path brief. The two defects the first pass found (start on the map re-teaching a done step; clipped
 * labels at 200% text) are fixed and kept here as regression tests.
 */
class DemoPathJourneyTest : LearnJourneyBase() {
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun tagPrefix(p: String) = SemanticsMatcher("tag $p*") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(p) == true }
    private fun nodesOf(pkg: String): List<PathNode> = awaitIo { deps.materials.materials().single { it.localPack?.packageId == pkg }.nodes }
    private fun spaceIdOf(pkg: String): String = awaitIo {
        val mat = deps.materials.materials().single { it.localPack?.packageId == pkg }
        deps.spaces.list().first { mat.member in it.members }.spaceId
    }
    private fun desc(nodeId: String): String {
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_$nodeId")); rule.waitForIdle()
        return rule.onNodeWithTag("route_node_$nodeId").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")
    }
    private fun state(nodeId: String): String {
        val d = desc(nodeId)
        return listOf(R.string.status_due, R.string.status_done, R.string.status_learned, R.string.status_needs_practice, R.string.route_state_current,
            R.string.status_new, R.string.route_state_locked, R.string.route_state_update).map { str(it) }.first { d.endsWith(it) }
    }
    private fun scrollHomeTo(tag: String) = waitFor("home tile $tag") { runCatching { rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false) }
    private fun openSpaceCard(spaceId: String) { scrollHomeTo("open_space_$spaceId"); tap(rule.onNodeWithTag("open_space_$spaceId")); waitForMap() }
    private fun waitForMap() = waitFor("route map") { exists(tagPrefix("route_node_")) || exists(hasTestTag("route_empty")) }
    private fun back() { systemBack(); rule.waitForIdle() }
    private fun doneCount(nodes: List<PathNode>) = nodes.count { state(it.nodeId).let { s -> s == str(R.string.status_done) || s == str(R.string.status_due) } }
    private fun assertRouteProgress(done: Int, total: Int) {
        waitFor("route progress $done/$total") {
            runCatching { rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_progress")); true }.getOrDefault(false) &&
                rule.onNodeWithTag("route_progress").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString() == str(R.string.route_progress_description, done, total)
        }
    }
    private fun meRecord(): String {
        openMe()
        val p = awaitIo { deps.learningProgress.projection() }
        val expected = completedDays(p.completed.size, p.days.size)
        scrollMeTo(expected)
        return expected
    }
    /** Texts that are laid out only in part (ellipsis or lines cut), named with their test tag so the culprit is obvious. */
    private fun clipped(): List<String> =
        rule.onAllNodes(SemanticsMatcher("text") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) != null }).fetchSemanticsNodes().mapNotNull { n ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            n.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.firstOrNull()
            if (r != null && ((0 until r.lineCount).any { r.isLineEllipsized(it) } || r.getLineEnd(r.lineCount - 1, visibleEnd = true) < r.layoutInput.text.length))
                (n.config.getOrNull(SemanticsProperties.TestTag) ?: "(no tag)").take(22) + ": " + n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } else null
        }
    private fun projectionNow() = awaitIo { deps.learningProgress.projection() }

    /** All texts on screen, scrolling the given list from top to bottom so lazily composed rows are included. */
    private fun collectAllText(list: String): List<String> {
        val seen = linkedSetOf<String>()
        seen += everythingShown()
        repeat(14) {
            rule.onNodeWithTag(list).performTouchInput { swipe(Offset(centerX, height * 0.8f), Offset(centerX, height * 0.2f), 150) }
            rule.waitForIdle()
            seen += everythingShown()
        }
        return seen.toList()
    }

    /** A listening socket on the debug build's configured API address: any request the app makes there is counted. */
    private inner class Spy : AutoCloseable {
        val hits = AtomicInteger()
        private val socket: ServerSocket? = runCatching { ServerSocket(18080, 5, java.net.InetAddress.getByName("127.0.0.1")) }.getOrNull()
        private val thread = socket?.let { s -> Thread { while (!s.isClosed) { try { s.accept().close(); hits.incrementAndGet() } catch (_: Exception) {} } }.also { it.isDaemon = true; it.start() } }
        val listening get() = socket != null
        override fun close() { runCatching { socket?.close() } }
    }

    // ---- Journey 1: Lin, the lived-in Attention Space ------------------------------------------------------

    /** Reads the target concept to its end from the explainer. A route step opens it directly: no assessment, no focus question. */
    private fun explainUntilRead(pkg: String, node: PathNode) {
        val target = (node.activity as ActivityRef.Tutor).activityId
        fun read() = projectionNow().completed.any { it.activityKind == com.anything2game.contract.progress.ActivityKind.TUTOR && it.activityId == target }
        waitFor("the explanation of ${node.title}") { has("explainer_stage") }
        assertFalse("a route step never shows the diagnostic", has("tutor_list") || has("focus_continue") || has("start_tutor"))
        var guard = 0
        while (!read() && guard++ < 12) {
            sceneReady(); readPages("page of ${node.title}")
            if (!read()) choose(pkg, "tutor_continue")
        }
        assertTrue("never finished ${node.title}", read())
    }

    private fun lin_attention_journey() {
        launch(); chooseLin()
        val nodes = nodesOf(attention); val total = nodes.size
        val spaceId = spaceIdOf(attention)
        val before = projectionNow()

        // Spaces home: the card of the Attention Space already shows progress.
        scrollHomeTo("open_space_$spaceId")
        val preDone = awaitIo { deps.spaces.route(spaceId, "en")!!.nodes.size }   // sanity: route and material agree
        assertEquals(total, preDone)

        openSpaceCard(spaceId)
        val states = nodes.map { state(it.nodeId) }
        val doneBefore = nodes.count { state(it.nodeId) == str(R.string.status_done) || state(it.nodeId) == str(R.string.status_due) }
        assertTrue("a lived-in map has finished steps", doneBefore in 1 until total)
        assertEquals("exactly one current step: $states", 1, states.count { it == str(R.string.route_state_current) })
        assertTrue("locked steps lie ahead", states.any { it == str(R.string.route_state_locked) })
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_start_bubble")); rule.waitForIdle()
        rule.onNodeWithTag("route_start_bubble").assertExists(); rule.onNodeWithTag("route_avatar").assertExists()
        assertRouteProgress(doneBefore, total)
        val current = nodes.first { state(it.nodeId) == str(R.string.route_state_current) }
        val next = nodes[nodes.indexOf(current) + 1]
        assertTrue("current is an explanation for this journey", current.activity is ActivityRef.Tutor)

        // The numbers elsewhere agree with the map.
        back(); waitForTag("home_SPACES")
        scrollHomeTo("open_space_$spaceId")
        assertTrue(textShown(spacesCounts(1, doneBefore, total)))
        val meBefore = meRecord()
        assertTrue(textShown(meBefore))

        // Tap the current node, read the sheet, start.
        openMeToSpaces(); openSpaceCard(spaceId)
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_${current.nodeId}")); rule.waitForIdle()
        tap(rule.onNodeWithTag("route_node_${current.nodeId}"))
        waitForTag("route_sheet")
        assertEquals(current.title, textOf("route_sheet_title"))
        startFromSheet(current)
        explainUntilRead(attention, current)
        back()
        waitFor("map after the explanation") { has("space_route") }

        // The map advanced: that node is done, the next one is the current step, and nothing else moved.
        waitFor("the finished node to show done") { state(current.nodeId) == str(R.string.status_done) }
        waitFor("the next node to become current") { state(next.nodeId) == str(R.string.route_state_current) }
        assertEquals(1, nodes.count { state(it.nodeId) == str(R.string.route_state_current) })
        assertRouteProgress(doneBefore + 1, total)
        val bubbleCount = rule.onAllNodesWithTag("route_start_bubble").fetchSemanticsNodes().size
        assertEquals("one Start bubble on the map", 1, bubbleCount)

        // Header, Spaces card and Me all say the same.
        back(); waitForTag("home_SPACES")
        scrollHomeTo("open_space_$spaceId")
        waitFor("card to say ${doneBefore + 1}") { textShown(spacesCounts(1, doneBefore + 1, total)) }
        val after = projectionNow()
        assertEquals(before.completed.size + 1, after.completed.size)
        val meAfter = meRecord()
        assertEquals(completedDays(after.completed.size, after.days.size), meAfter)
        assertNotEquals(meBefore, meAfter)
    }
    private fun startFromSheet(node: PathNode) {
        val start = rule.onNodeWithTag("open_node_${node.nodeId}")
        waitFor("Start enabled") { runCatching { start.assertIsEnabled(); true }.getOrDefault(false) }
        tap(start)
    }
    private fun openMeToSpaces() { tap(rule.onNodeWithTag("tab_SPACES")); waitForTag("home_SPACES") }

    @Test fun journey1_lin_resumes_the_attention_explanation_and_the_map_advances_everywhere() = lin_attention_journey()

    @Test fun journey1_in_chinese_the_same_journey_works() {
        RuntimeEnvironment.setQualifiers("+zh")
        lin_attention_journey()
    }

    @Test fun journey1_start_on_lins_current_step_opens_that_concept_and_does_not_re_teach_a_step_the_map_calls_done() {
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        openSpaceCard(spaceIdOf(attention))
        val current = nodes.first { state(it.nodeId) == str(R.string.route_state_current) }
        val doneTitles = nodes.filter { state(it.nodeId) == str(R.string.status_done) }.map { it.title }
        tap(rule.onNodeWithTag("route_node_${current.nodeId}")); waitForTag("route_sheet")
        startFromSheet(current)
        waitFor("the explanation of the step") { has("explainer_stage") }
        // Straight to the step's own concept: no starting assessment, no focus question, no 4-question diagnostic.
        assertFalse(has("tutor_list")); assertFalse(has("focus_continue")); assertFalse(has("start_tutor"))
        assertFalse(textShown(str(R.string.tutor_assessment_title)))
        sceneReady()
        val session = cache(attention).session
        val concepts = TutorRules.orderedQuestions(session)
        assertEquals((current.activity as ActivityRef.Tutor).activityId, concepts[session.conceptPosition].conceptId)
        assertEquals(current.title, concepts[session.conceptPosition].concept)
        val shown = everythingShown().joinToString("\n")
        doneTitles.forEach { assertFalse("the explainer teaches \"$it\", which the map shows as done", shown.contains(it) && it != current.title) }
        assertTrue("the earlier concepts are still reachable from the concept list", TutorRules.introduced(session) > session.conceptPosition)
    }

    // ---- Journey 2: a practice node opens the right frame ------------------------------------------------

    @Test fun journey2_resnet_current_practice_node_opens_the_case_frame_resumes_and_finishing_updates_the_map() {
        launch(); chooseLin()
        val nodes = nodesOf(resnet); val total = nodes.size
        openSpaceCard(spaceIdOf(resnet))
        val current = nodes.first { state(it.nodeId) == str(R.string.route_state_current) }
        val practice = current.activity as ActivityRef.Practice
        assertEquals("the sample student plays ResNet as a Case", FrameId.CASE, practice.frame)
        val doneBefore = doneCount(nodes)
        assertRouteProgress(doneBefore, total)
        val level = Level(resnet, FrameId.CASE)
        val saved = level.session()!!
        assertFalse(saved.isFinished)
        tap(rule.onNodeWithTag("route_node_${current.nodeId}"))
        waitForTag("route_sheet")
        assertTrue("the sheet offers Continue for the half-played case", textShown(str(R.string.route_continue)))
        assertTrue(textOf("route_sheet_kind").startsWith(str(R.string.route_kind_case)))
        tap(rule.onNodeWithTag("open_node_${current.nodeId}"))
        waitFor("play screen") { exists(hasContentDescription(str(R.string.action_back))) }
        assertEquals(FrameId.CASE, level.session()!!.frameId)
        assertEquals("resumes where it stopped", saved.position, level.session()!!.position)
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
        waitForText(str(R.string.stamp_case_closed))
        tapText(str(R.string.recap_library))
        // Back home from the recap: the student expects the map they started from.
        waitFor("a Space screen") { has("space_route") || has("space_materials") }
        assertTrue("the recap's 'Back home' returns to the route map the node was opened from", has("space_route"))
        waitFor("the practice node to be done") { state(current.nodeId).let { it == str(R.string.status_done) || it == str(R.string.status_due) } }
        assertRouteProgress(doneBefore + 1, total)
    }

    // ---- Journey 3: locked node ----------------------------------------------------------------------------

    @Test fun journey3_tapping_a_locked_node_only_says_what_to_finish_first() {
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        openSpaceCard(spaceIdOf(attention))
        val locked = nodes.first { state(it.nodeId) == str(R.string.route_state_locked) }
        val waitingFor = nodes[nodes.indexOf(locked) - 1].title
        val doneBefore = doneCount(nodes)
        val diskBefore = projectionNow()
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_${locked.nodeId}"))
        tap(rule.onNodeWithTag("route_node_${locked.nodeId}"))
        waitForText(str(R.string.route_locked_hint, waitingFor))
        assertFalse("no sheet opens", has("route_sheet"))
        assertTrue("still on the map", has("space_route"))
        assertFalse(has("explainer_stage")); assertFalse(has("game_board")); assertFalse(has("tutor_list"))
        assertEquals(doneBefore, doneCount(nodes))
        assertEquals(diskBefore.completed, projectionNow().completed)
        // Tapping it again just repeats the hint (no stacking, no crash).
        tap(rule.onNodeWithTag("route_node_${locked.nodeId}"))
        waitForText(str(R.string.route_locked_hint, waitingFor))
        assertFalse(has("route_sheet"))
    }

    // ---- Journey 4: Playground, both mechanics, both stages ---------------------------------------------------

    private fun playground(language: String = "en") { launch(); chooseGuest(); tap(rule.onNodeWithTag("tab_PLAYGROUND")); waitForTag("home_PLAYGROUND") }
    /** The shelf loads its games after the screen appears: a negative check ("no Continue button") is only meaningful once the tiles are there. */
    private fun shelfLoaded() { waitForTag("game_partition"); waitForTag("game_allocation"); rule.waitForIdle() }
    private fun specOf(id: String): GameSpec = ReviewedGames.all(id.substringAfterLast('-')).first { it.gameId == id }
    private fun versionOf(id: String) = specOf(id).let { ContentVersion(it.gameId, it.gameVersion) }
    private fun run(id: String) = awaitIo { deps.learningProgress.checkpoint(versionOf(id)) }?.let { GameRuntime.replay(specOf(id), it) }
    private fun badgeOn(tag: String, label: String) = rule.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** One question per tap: the middle cut, then Ask. Plays the current stage to its end. */
    private fun playPartitionStage(id: String, slideFirst: Boolean = false) {
        var first = true
        while (true) {
            val r = run(id)!!
            if (r.status != StageStatus.PLAYING) return
            val b = r.board as PartitionBoard
            val mid = (b.low + b.high) / 2
            waitForTag("cut_$mid")
            if (first && slideFirst) {
                val grid = rule.onNodeWithTag("tile_grid"); val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
                fun centre(n: Int) = rule.onNodeWithTag("cut_$n").fetchSemanticsNode().boundsInRoot.center - origin
                val from = if (mid > b.low) mid - 1 else mid
                grid.performTouchInput { down(centre(from)); moveTo(centre(from) + Offset(12f, 0f)); moveTo(centre(mid)); up() }
            } else tap(rule.onNodeWithTag("cut_$mid"))
            first = false
            val n = r.checkpoint.actions.size
            tap(rule.onNodeWithTag("split_submit"))
            waitFor("question spent") { run(id)!!.checkpoint.actions.size > n }
            rule.waitForIdle()
        }
    }

    private fun allocationNeeds(id: String, stage: Int): Pair<Int, Int> {
        val c = specOf(id).stages[stage].config as AllocationConfig
        val first = (c.firstGoal + c.firstYield - 1) / c.firstYield
        return first to (c.tokens - first)
    }
    private fun deliver() {
        waitFor("deliver enabled") { runCatching { rule.onNodeWithTag("allocation_submit").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("allocation_submit"))
    }
    private fun placeTokens(first: Int, second: Int) {
        fun place(zone: String, times: Int) = repeat(times) {
            val control = rule.onNodeWithTag("place_$zone")
            waitFor("token controls ready") { runCatching { control.assertIsEnabled(); true }.getOrDefault(false) }
            tap(control)
        }
        place("FIRST", first); place("SECOND", second)
    }

    @Test fun journey4_partition_game_through_both_stages_with_stars_and_the_continue_badge() {
        playground(); shelfLoaded()
        assertFalse("nothing to continue on a fresh Playground", has("resume_game"))
        assertTrue("fresh games say New", badgeOn("game_partition", str(R.string.playground_new)))
        openGame(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
        val id = "locker-search-en"
        playPartitionStage(id, slideFirst = true)
        waitForTag("game_result"); waitForTag("game_stars"); waitForText(str(R.string.games_won))
        assertTrue(textShown(str(R.string.games_stamp_clear)))
        // Leave in the middle of the whole game (stage 1 won, transfer not started): the shelf offers to continue.
        back(); waitForTag("home_PLAYGROUND")
        waitForTag("resume_game")
        assertTrue(badgeOn("game_partition", str(R.string.playground_continue)))
        openGame(rule.onNodeWithTag("resume_game")); waitForTag("game_advance")
        assertTrue("won round is still shown after coming back", has("game_stars"))
        tap(rule.onNodeWithTag("game_advance")); waitForTag("new_conditions")
        waitForText(str(R.string.games_transfer))
        playPartitionStage(id)
        waitForText(str(R.string.games_complete)); waitForTag("game_stars")
        val stars = awaitIo { deps.learningProgress.bestStars(versionOf(id)) }
        assertTrue("a completed game has stars ($stars)", stars in 1..3)
        back(); waitForTag("home_PLAYGROUND"); shelfLoaded()
        assertFalse("a finished game is no longer 'Continue'", has("resume_game"))
        assertFalse(badgeOn("game_partition", str(R.string.playground_continue)))
        assertFalse(badgeOn("game_partition", str(R.string.playground_new)))
        waitFor("stars on the tile") { rule.onAllNodes(hasContentDescription(str(R.string.playground_stars_cd, stars))).fetchSemanticsNodes().isNotEmpty() }
        // Me reflects exactly one completed activity.
        val p = projectionNow()
        assertEquals(1, p.completed.size)
        openMe(); scrollMeTo(completedDays(1, 1))
    }

    @Test fun journey4_allocation_game_through_both_stages_with_a_real_drag() {
        playground()
        waitForTag("game_allocation"); openGame(rule.onNodeWithTag("game_allocation")); waitForTag("token_tray")
        val id = "workshop-en"
        // One token by dragging, the rest by the place buttons.
        val tray = rule.onNodeWithTag("token_tray"); val origin = tray.fetchSemanticsNode().boundsInRoot.topLeft
        val zone = rule.onNodeWithTag("place_SECOND").fetchSemanticsNode().boundsInRoot.center - origin
        tray.performTouchInput { down(Offset(20f, 20f)); moveTo(Offset(50f, 40f)); moveTo(Offset(90f, 80f)); moveTo(zone); up() }
        waitForText("5 helpers left")
        val (f1, s1) = allocationNeeds(id, 0)
        placeTokens(f1, s1 - 1)
        waitForText("0 helpers left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForTag("game_stars"); waitForText(str(R.string.games_won))
        tap(rule.onNodeWithTag("game_advance")); waitForTag("new_conditions")
        val (f2, s2) = allocationNeeds(id, 1)
        placeTokens(f2, s2)
        waitForText("0 helpers left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForText(str(R.string.games_complete)); waitForTag("game_stars")
        val stars = awaitIo { deps.learningProgress.bestStars(versionOf(id)) }
        assertTrue(stars in 1..3)
        back(); waitForTag("home_PLAYGROUND"); shelfLoaded()
        waitFor("stars on the tile") { rule.onAllNodes(hasContentDescription(str(R.string.playground_stars_cd, stars))).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(has("resume_game"))
        openMe(); scrollMeTo(completedDays(1, 1))
    }

    @Test fun journey4_a_lost_round_can_be_retried_and_gives_no_stars_or_completion() {
        playground()
        waitForTag("game_allocation"); openGame(rule.onNodeWithTag("game_allocation")); waitForTag("place_FIRST")
        placeTokens(6, 0)            // all tokens to one zone: the goal is missed
        waitForText("0 helpers left")
        waitFor("deliver enabled") { runCatching { rule.onNodeWithTag("allocation_submit").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForTag("retry_round")
        assertFalse(has("game_stars")); assertFalse(has("game_advance"))
        assertTrue(textShown(str(R.string.games_lost)) || textShown(str(R.string.games_stamp_miss)))
        tap(rule.onNodeWithTag("retry_round")); waitForText("6 helpers left")
        assertEquals(0, awaitIo { deps.learningProgress.bestStars(versionOf("workshop-en")) })
        assertTrue(projectionNow().completed.isEmpty())
    }

    @Test fun journey4_peeking_into_a_game_does_not_turn_it_into_continue() {
        playground()
        waitForTag("game_partition"); openGame(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
        back(); waitForTag("home_PLAYGROUND"); shelfLoaded()
        assertFalse("a game never played is not 'Continue'", has("resume_game"))
        assertFalse(badgeOn("game_partition", str(R.string.playground_continue)))
    }

    // ---- Journey 5: + Add a game --------------------------------------------------------------------------------

    private fun addGame(topic: String) {
        tap(rule.onNodeWithTag("add_game")); waitForTag("add_topic")
        rule.onNodeWithTag("add_topic").performTextInput(topic)
        tap(rule.onNodeWithTag("add_generate"))
    }

    @Test fun journey5_known_topics_open_the_matching_preset_with_the_demo_badge_and_never_touch_the_network() {
        Spy().use { spy ->
            assertTrue("the debug API port is free to spy on", spy.listening)
            playground()
            addGame("git bisect")
            waitForGame(); waitForText("Hunt the broken commit"); rule.onNodeWithTag("demo_badge").assertExists()
            assertTrue(textShown(str(R.string.games_demo_badge)))
            back(); waitForTag("home_PLAYGROUND")
            addGame("Opportunity Cost  "); waitForGame(); waitForText("Two workshops"); rule.onNodeWithTag("demo_badge").assertExists()
            back(); waitForTag("home_PLAYGROUND")
            addGame("机会成本"); waitForGame(); rule.onNodeWithTag("demo_badge").assertExists()
            assertEquals("no request to the server", 0, spy.hits.get())
        }
    }

    @Test fun journey5_in_chinese_both_topics_find_chinese_games() {
        RuntimeEnvironment.setQualifiers("+zh")
        Spy().use { spy ->
            playground()
            addGame("git bisect"); waitForGame(); waitForTag("demo_badge")
            waitForText(specOf("git-bisect-zh").title)
            back(); waitForTag("home_PLAYGROUND")
            addGame("机会成本"); waitForGame()
            waitForText(specOf("workshop-zh").title)
            back(); waitForTag("home_PLAYGROUND")
            addGame("量子色动力学"); waitForTag("chooser"); waitForText(str(R.string.add_choose))
            tap(rule.onNodeWithTag("chooser_farm-crops-zh")); waitForGame(); waitForTag("demo_badge")
            assertEquals(0, spy.hits.get())
        }
    }

    @Test fun journey5_an_unknown_topic_offers_the_prepared_games_and_names_the_topic() {
        Spy().use { spy ->
            playground()
            addGame("quantum chromodynamics")
            waitForTag("chooser")
            waitForText(str(R.string.add_no_match, "quantum chromodynamics")); waitForText(str(R.string.add_choose))
            assertEquals("every prepared game is offered", ReviewedGames.all("en").size, rule.onAllNodes(tagPrefix("chooser_")).fetchSemanticsNodes().size)
            assertEquals(0, spy.hits.get())
        }
    }

    @Test fun journey5_the_sheet_can_be_closed_at_every_stage_and_survives_rotation_with_the_typed_topic() {
        playground()
        tap(rule.onNodeWithTag("add_game")); waitForTag("add_topic")
        rule.onNodeWithTag("add_topic").performTextInput("binary search")
        scenario!!.recreate(); waitForTag("add_topic")
        rule.onNodeWithTag("add_topic").assertTextContains("binary search")
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scenario!!.recreate(); waitForTag("add_topic")
        tap(rule.onNodeWithTag("add_generate")); waitForTag("generating")
        back()                                   // system back during the sequence returns to the form, nothing is created
        waitForTag("add_topic"); assertFalse(has("game_board"))
        tap(rule.onNodeWithTag("add_close")); waitFor("sheet gone") { !has("add_sheet") }
        assertTrue(has("home_PLAYGROUND"))
    }

    @Test fun journey5_unrelated_topics_do_not_open_an_unrelated_game() {
        playground()
        for (topic in listOf("digital twin", "an example of recursion", "default settings")) {
            addGame(topic)
            waitFor("chooser or board for $topic") { has("chooser") || has("game_board") }
            assertTrue("\"$topic\" opened a game instead of the 'no ready-made game' chooser", has("chooser"))
            tap(rule.onNodeWithTag("add_close")); waitFor("sheet closed") { !has("add_sheet") }
        }
    }

    // ---- Journey 6: guest, empty states, no developer jargon ----------------------------------------------------------

    private val jargon = Regex("(?i)\\b(mock|fixture|stub|lorem|todo|exception|stack ?trace|null|undefined|kotlin|java\\.|localhost|127\\.0\\.0\\.1|http://|econnrefused|NOT_CONFIGURED)\\b|sha256|source-[0-9a-f]{12}|local-[0-9a-f]{12}|doc_demo|pkg_demo|demo_[a-z]+|\\b[0-9a-f]{24,}\\b|%\\d?\\$?[sd]")
    private fun assertNoJargon(where: String, texts: List<String>) {
        val bad = texts.filter { jargon.containsMatchIn(it) }
        assertTrue("developer jargon / raw ids on $where: $bad", bad.isEmpty())
    }

    @Test fun journey6_guest_demo_path_screens_carry_no_developer_jargon_or_raw_ids() {
        launch(); chooseGuest()
        waitForTag("home_SPACES"); waitFor("cards") { exists(tagPrefix("open_space_")) }
        assertNoJargon("Spaces home", collectAllText("home_SPACES"))
        // A map and its sheet and the materials list.
        val id = spaceIdOf(attention)
        openSpaceCard(id)
        assertNoJargon("the Attention map", collectAllText("space_route"))
        tap(rule.onNodeWithTag("show_materials")); waitForTag("space_materials")
        assertNoJargon("the Attention materials", collectAllText("space_materials"))
        tap(rule.onNodeWithTag("show_route")); waitForMap()
        val cur = nodesOf(attention).first { state(it.nodeId) == str(R.string.route_state_current) }
        tap(rule.onNodeWithTag("route_node_${cur.nodeId}")); waitForTag("route_sheet")
        assertNoJargon("the node sheet", everythingShown())
        tap(rule.onNodeWithTag("route_sheet_source")); rule.waitForIdle()
        assertNoJargon("the source chip / evidence", everythingShown())
        // Playground and Me.
        back(); back()
        waitFor("a tab bar") { has("tab_PLAYGROUND") }
        tap(rule.onNodeWithTag("tab_PLAYGROUND")); waitForTag("home_PLAYGROUND")
        assertNoJargon("Playground", collectAllText("home_PLAYGROUND"))
        openGame(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
        assertNoJargon("a game board", collectAllText("game_board"))
        back(); openMe()
        assertNoJargon("Me", collectAllText("home_PROFILE"))
    }

    @Test fun journey6_an_empty_space_and_a_fresh_playground_are_friendly_with_one_clear_action() {
        launch(); chooseGuest()
        waitFor("create available") { runCatching { rule.onNodeWithTag("space_create").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("space_create"))
        rule.onNodeWithTag("space_name").performTextInput("Biology notes")
        tap(rule.onNodeWithTag("save_space"))
        waitForTag("route_empty")                        // a new Space opens on its route: the empty state with the one clear action
        assertFalse("not on the materials tab", has("space_materials"))
        rule.onNodeWithTag("show_route").assertIsSelected()
        assertTrue(textShown(str(R.string.route_empty_title))); assertTrue(textShown(str(R.string.route_empty_no_materials)))
        waitForTag("route_add_materials")
        assertNoJargon("an empty Space", everythingShown())
        assertFalse("no progress strip with nothing to progress", has("route_progress"))
        tap(rule.onNodeWithTag("route_add_materials"))
        waitForTag("space_materials")
        assertNoJargon("the empty Space's materials", collectAllText("space_materials"))
        // Each of the actions on this screen leads somewhere: at least one way to add material is offered.
        assertTrue("materials offers a way to add something", exists(hasTestTag("add_existing")) || exists(hasText(str(R.string.spaces_import))) || textShown(str(R.string.spaces_add)))
        back(); waitForTag("home_SPACES")
        tap(rule.onNodeWithTag("tab_PLAYGROUND")); waitForTag("home_PLAYGROUND"); shelfLoaded()
        assertFalse(has("resume_game"))
        assertNoJargon("a fresh Playground", collectAllText("home_PLAYGROUND"))
    }

    @Test fun journey6_refresh_from_server_in_an_offline_demo_is_not_a_dead_end_with_a_raw_error() {
        launch(); chooseGuest()
        waitForTag("home_SPACES")
        val refresh = hasText(str(R.string.spaces_sync)) and hasClickAction()
        waitFor("cards") { exists(tagPrefix("open_space_")) }
        rule.onNodeWithTag("home_SPACES").performScrollToIndex(10); rule.waitForIdle()
        waitFor("enabled") { runCatching { rule.onNode(refresh).assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNode(refresh))
        waitFor("some feedback or quiet") { Thread.sleep(300); !has("home_SPACES") || everythingShown().any { it.startsWith(str(R.string.spaces_error, "").trim().take(8)) } || true }
        rule.waitForIdle(); Thread.sleep(1500); rule.waitForIdle()
        val shown = collectAllText("home_SPACES")
        java.io.File("/tmp/qa-refresh-shown.txt").writeText(shown.joinToString("\n"))
        assertNoJargon("Spaces home after tapping Refresh from server", shown)
        assertFalse("a raw connection error is shown: ${shown.filter { it.contains(str(R.string.spaces_error, "").trim().take(10)) }}",
            shown.any { it.startsWith(str(R.string.spaces_error, "").trim().take(10)) })
    }

    // ---- Journey 7: Chinese locale and font scale 2.0 -------------------------------------------------------------------

    @Test fun journey7_chinese_map_sheet_and_locked_hint_are_fully_chinese() {
        RuntimeEnvironment.setQualifiers("+zh")
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        openSpaceCard(spaceIdOf(attention))
        val chrome = everythingShown().filter { t -> nodes.none { n -> t.contains(n.title) } && !t.contains(title(attention)) }
        val english = chrome.filter { Regex("[A-Za-z]{3,}").containsMatchIn(it) }
        assertTrue("untranslated map chrome: $english", english.isEmpty())
        val locked = nodes.first { state(it.nodeId) == str(R.string.route_state_locked) }
        tap(rule.onNodeWithTag("route_node_${locked.nodeId}"))
        waitFor("hint") { exists(hasText("先完成", substring = true)) || exists(hasText("完成", substring = true)) }
        assertTrue(cjk.containsMatchIn(everythingShown().first { it.contains("完成") }))
        val current = nodes.first { state(it.nodeId) == str(R.string.route_state_current) }
        tap(rule.onNodeWithTag("route_node_${current.nodeId}")); waitForTag("route_sheet")
        val sheet = listOf(textOf("route_sheet_kind"), str(R.string.route_start)).joinToString()
        assertTrue("sheet kind line is Chinese: $sheet", cjk.containsMatchIn(sheet))
    }

    @Test fun journey7_font_scale_two_on_the_map_keeps_labels_complete_and_the_sheet_reachable() {
        RuntimeEnvironment.setFontScale(2f)
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        openSpaceCard(spaceIdOf(attention))
        val cut = clipped().filterNot { it.startsWith("route_label_") }
        assertTrue("clipped / ellipsised texts on the 200% map: $cut", cut.isEmpty())
        val current = nodes.first { state(it.nodeId) == str(R.string.route_state_current) }
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_${current.nodeId}")); rule.waitForIdle()
        tap(rule.onNodeWithTag("route_node_${current.nodeId}")); waitForTag("route_sheet")
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val button = boundsOf("open_node_${current.nodeId}"); val sheet = boundsOf("route_sheet")
        assertTrue("Start button $button inside the screen $root", button.top >= 0f && button.bottom <= root.bottom + 1f)
        assertTrue("sheet $sheet inside the screen", sheet.top >= 0f)
        val sheetClipped = clipped().filter { it.startsWith("route_sheet") && !it.startsWith("route_sheet_source") }
        assertTrue("sheet title / kind are not clipped: $sheetClipped", sheetClipped.isEmpty())
        tap(rule.onNodeWithTag("open_node_${current.nodeId}"))
        waitFor("started") { has("explainer_stage") }
        assertFalse(has("tutor_list"))
    }

    @Test fun journey7_font_scale_two_step_labels_and_the_source_chip_wrap_in_full() {
        RuntimeEnvironment.setFontScale(2f)
        launch(); chooseLin()
        openSpaceCard(spaceIdOf(attention))
        nodesOf(attention).forEach { desc(it.nodeId) }
        assertTrue("ellipsised step labels: ${clipped()}", clipped().none { it.startsWith("route_label_") })
        tap(rule.onNodeWithTag("route_node_${nodesOf(attention).first { state(it.nodeId) == str(R.string.route_state_current) }.nodeId}")); waitForTag("route_sheet")
        assertTrue("source chip in the sheet is cut: ${clipped()}", clipped().none { it.startsWith("route_sheet_source") })
    }

    @Test fun journey7_font_scale_two_on_both_game_boards_keeps_every_control_reachable() {
        RuntimeEnvironment.setFontScale(2f)
        playground()
        waitForTag("game_partition"); openGame(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
        tap(rule.onNodeWithTag("cut_3"))
        val cutTexts = clipped()
        assertTrue("clipped texts on the 200% partition board: $cutTexts", cutTexts.isEmpty())
        tap(rule.onNodeWithTag("split_submit"))
        waitFor("question spent") { run("locker-search-en")!!.checkpoint.actions.size == 1 }
        back(); waitForTag("home_PLAYGROUND"); waitForTag("game_allocation")
        openGame(rule.onNodeWithTag("game_allocation")); waitForTag("place_FIRST")
        placeTokens(1, 1)
        waitForText("4 helpers left")
        val texts = clipped()
        assertTrue("clipped texts on the 200% allocation board: $texts", texts.isEmpty())
        placeTokens(2, 2)
        deliver()
        waitFor("a result") { has("game_result") }
    }

    // ---- Journey 8: rotation and process restart --------------------------------------------------------------------------

    @Test fun journey8_partition_game_keeps_its_state_through_rotation_recreation_and_a_process_restart() {
        playground()
        waitForTag("game_partition"); openGame(rule.onNodeWithTag("game_partition")); waitForTag("cut_4")
        tap(rule.onNodeWithTag("cut_6"))
        val n0 = run("locker-search-en")!!.checkpoint.actions.size
        tap(rule.onNodeWithTag("split_submit")); waitFor("asked") { run("locker-search-en")!!.checkpoint.actions.size > n0 }
        val before = run("locker-search-en")!!.board as PartitionBoard
        tap(rule.onNodeWithTag("cut_${(before.low + before.high) / 2}"))
        RuntimeEnvironment.setQualifiers("+land"); rule.waitForIdle()
        waitForTag("split_submit")
        val after = run("locker-search-en")!!.board as PartitionBoard
        assertEquals(before, after)
        assertTrue("the cut being chosen survives rotation", textShown(str(R.string.games_pick, (before.low + before.high) / 2)))
        RuntimeEnvironment.setQualifiers("+port"); rule.waitForIdle()
        scenario!!.recreate(); waitForTag("split_submit")
        assertEquals(before, run("locker-search-en")!!.board)
        // Process death: nothing but the disk remains.
        restartProcess()
        waitFor("home after restart") { has("home_SPACES") || has("home_PLAYGROUND") || has("game_board") }
        if (!has("home_PLAYGROUND") && !has("game_board")) { tap(rule.onNodeWithTag("tab_PLAYGROUND")); waitForTag("home_PLAYGROUND") }
        if (!has("game_board")) { waitForTag("resume_game"); openGame(rule.onNodeWithTag("resume_game")) }
        waitForTag("split_submit")
        assertEquals("the board is where it was", before, run("locker-search-en")!!.board)
        waitForText(str(R.string.games_queries_left, before.queriesLeft, "questions"))
    }

    @Test fun journey8_map_with_an_open_sheet_survives_rotation_and_a_route_survives_a_process_restart() {
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        val spaceId = spaceIdOf(attention)
        openSpaceCard(spaceId)
        val current = nodes.first { state(it.nodeId) == str(R.string.route_state_current) }
        tap(rule.onNodeWithTag("route_node_${current.nodeId}")); waitForTag("route_sheet")
        RuntimeEnvironment.setQualifiers("+land"); rule.waitForIdle()
        waitForTag("route_sheet")
        assertEquals(current.title, textOf("route_sheet_title"))
        RuntimeEnvironment.setQualifiers("+port"); rule.waitForIdle()
        scenario!!.recreate(); waitForTag("route_sheet")
        assertEquals(current.title, textOf("route_sheet_title"))
        // Mid-explanation process restart: the saved explanation is where the student left it.
        startFromSheet(current)
        explainUntilReadingStarted()
        val position = cache(attention).session.conceptPosition
        val messages = cache(attention).session.messages.size
        restartProcess()
        waitFor("after restart") { has("home_SPACES") || has("explainer_stage") }
        if (has("home_SPACES")) {
            openSpaceCard(spaceId)
            tap(rule.onNodeWithTag("route_node_${current.nodeId}")); waitForTag("route_sheet")
            startFromSheet(current)
        }
        waitFor("the explainer again") { has("explainer_stage") }
        assertEquals("the saved concept position is kept", position, cache(attention).session.conceptPosition)
        assertEquals("nothing was lost from the saved conversation", messages, cache(attention).session.messages.size)
    }

    private fun explainUntilReadingStarted() {
        waitFor("the explanation, straight from the route step") { has("explainer_stage") }
        assertFalse(has("tutor_list"))
        sceneReady()
    }

    // ---- Journey 9: the product decisions after the first QA pass ---------------------------------------------------

    /** Credits everything before [node] the way a student who got that far would have, so the step can be tapped. */
    private fun creditBefore(nodes: List<PathNode>, node: PathNode) = awaitIo {
        nodes.takeWhile { it.nodeId != node.nodeId }.forEach { before ->
            deps.learningProgress.record(before.activity, if (before.activity is ActivityRef.Tutor) ActivityOutcome.READ else ActivityOutcome.INDEPENDENT, 1_000_000L)
        }
    }

    @Test fun journey9_start_on_the_first_step_of_a_fresh_route_opens_that_concept_without_any_diagnostic() {
        launch(); chooseGuest()
        val nodes = nodesOf(attention)
        val first = nodes.first()
        assertTrue(first.activity is ActivityRef.Tutor)
        openSpaceCard(spaceIdOf(attention))
        tap(rule.onNodeWithTag("route_node_${first.nodeId}")); waitForTag("route_sheet")
        startFromSheet(first)
        waitFor("the explanation") { has("explainer_stage") }
        assertFalse("no focus step", has("focus_continue")); assertFalse("no diagnostic", has("tutor_list"))
        assertFalse(textShown(str(R.string.tutor_assessment_title))); assertFalse(textShown(str(R.string.tutor_diagnostic_title)))
        sceneReady()
        val session = cache(attention).session
        assertEquals(0, session.conceptPosition)
        assertEquals((first.activity as ActivityRef.Tutor).activityId, session.questions[0].conceptId)
        assertTrue(session.assessment.isNotEmpty() && session.assessment.all { it.evidence == Understanding.NOT_ASKED })
        // A neutral lead-in, not "you got this right" or "you weren't sure": nobody was asked.
        val message = lastTutorMessage(attention).text
        assertEquals(TutorRules.leadIn(Understanding.NOT_ASKED, false), message.lines()[1])
        assertEquals(first.title, message.lines()[0])
    }

    @Test fun journey9_in_chinese_start_on_a_route_step_opens_the_concept_with_a_chinese_lead_in() {
        RuntimeEnvironment.setQualifiers("+zh")
        launch(); chooseGuest()
        val nodes = nodesOf(zhSpaced)
        val first = nodes.first()
        openSpaceCard(spaceIdOf(zhSpaced))
        tap(rule.onNodeWithTag("route_node_${first.nodeId}")); waitForTag("route_sheet")
        startFromSheet(first)
        waitFor("the explanation") { has("explainer_stage") }
        assertFalse(has("tutor_list")); assertFalse(has("focus_continue"))
        sceneReady()
        val message = lastTutorMessage(zhSpaced).text
        assertEquals(TutorRules.leadIn(Understanding.NOT_ASKED, true), message.lines()[1])
        assertTrue(cjk.containsMatchIn(message.lines()[1]))
    }

    private fun gameJourney(pkg: String, id: String, mechanic: String) {
        launch(); chooseGuest()
        val nodes = nodesOf(pkg)
        val game = nodes.first { it.activity is ActivityRef.Game }
        assertEquals(id, (game.activity as ActivityRef.Game).contentId)
        creditBefore(nodes, game)
        val spaceId = spaceIdOf(pkg)
        val spaceTitle = awaitIo { deps.spaces.list().first { it.spaceId == spaceId }.title }
        openSpaceCard(spaceId)
        assertEquals(str(R.string.route_state_current), state(game.nodeId))
        val doneBefore = doneCount(nodes)
        tap(rule.onNodeWithTag("route_node_${game.nodeId}")); waitForTag("route_sheet")
        assertEquals(game.title, textOf("route_sheet_title"))
        assertTrue(textOf("route_sheet_kind").startsWith(str(R.string.route_kind_game)))
        startFromSheet(game)
        waitForGame(); waitForText(game.title)
        assertFalse("no Back-to button before the game is finished", has("back_to_space"))
        if (mechanic == "partition") {
            playPartitionStage(id); waitForTag("game_stars"); tap(rule.onNodeWithTag("game_advance")); waitForTag("new_conditions")
            playPartitionStage(id)
        } else {
            val (f1, s1) = allocationNeeds(id, 0); placeTokens(f1, s1)
            deliver(); waitForTag("game_stars"); tap(rule.onNodeWithTag("game_advance")); waitForTag("new_conditions")
            val (f2, s2) = allocationNeeds(id, 1); placeTokens(f2, s2)
            deliver()
        }
        waitForText(str(R.string.games_complete))
        // Finished from a route: the way back to that Space is named, and Play again is still there.
        waitForTag("back_to_space")
        assertTrue(textShown(str(R.string.games_back_to, spaceTitle)))
        assertTrue(textShown(str(R.string.games_replay)))
        tap(rule.onNodeWithTag("back_to_space"))
        waitFor("the map of that Space") { has("space_route") }
        waitFor("the game step to show done") { state(game.nodeId).let { it == str(R.string.status_done) || it == str(R.string.status_due) } }
        assertRouteProgress(doneBefore + 1, nodes.size)
        assertEquals(1, projectionNow().completed.count { it.activityKind == com.anything2game.contract.progress.ActivityKind.GAME })
    }

    @Test fun journey9_a_partition_game_step_in_the_resnet_route_is_played_credited_and_returns_to_its_space() = gameJourney(resnet, "layer-fault-en", "partition")

    @Test fun journey9_an_allocation_game_step_in_the_attention_route_is_played_credited_and_returns_to_its_space() = gameJourney(attention, "gpu-budget-en", "allocation")

    @Test fun journey9_a_game_opened_from_the_playground_has_no_back_to_space_button() {
        playground(); shelfLoaded()
        openGame(rule.onNodeWithTag("game_allocation")); waitForTag("token_tray")
        val id = "workshop-en"
        val (f1, s1) = allocationNeeds(id, 0); placeTokens(f1, s1)
        deliver(); waitForTag("game_stars"); tap(rule.onNodeWithTag("game_advance")); waitForTag("new_conditions")
        val (f2, s2) = allocationNeeds(id, 1); placeTokens(f2, s2)
        deliver()
        waitForText(str(R.string.games_complete)); waitForTag("restart_game")
        assertFalse(has("back_to_space"))
    }

    private fun spaceOrder(): List<String> {
        val seen = linkedMapOf<String, Float>()
        waitFor("the Space cards") { exists(tagPrefix("open_space_")) }
        repeat(12) {
            rule.onAllNodes(tagPrefix("open_space_")).fetchSemanticsNodes().forEach { n ->
                seen.getOrPut(n.config[SemanticsProperties.TestTag].removePrefix("open_space_")) { 0f }
            }
            rule.onNodeWithTag("home_SPACES").performTouchInput { swipe(Offset(centerX, height * 0.8f), Offset(centerX, height * 0.2f), 150) }
            rule.waitForIdle()
        }
        return seen.keys.toList()
    }
    private fun languageOf(spaceId: String): String = awaitIo<String> {
        val inventory = deps.materials.materials(); val space = deps.spaces.list().first { it.spaceId == spaceId }
        inventory.filter { it.member in space.members || it.localPack?.let { p -> p in space.members } == true }.map { it.language }.first()
    }

    @Test fun journey9_spaces_in_the_apps_language_come_first_and_the_others_carry_a_language_chip() {
        launch(); chooseGuest()
        val order = spaceOrder()
        val languages = order.map(::languageOf)
        assertTrue("both languages are present in the sample shelf: $languages", "en" in languages && "zh" in languages)
        assertEquals("English Spaces first, then Chinese: $languages", languages.sortedBy { it != "en" }, languages)
        order.forEach { id ->
            val chip = rule.onAllNodes(hasTestTag("space_language_$id"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag("space_language_$id")); true }.getOrDefault(false)
            assertEquals("only the other-language Spaces carry a chip ($id)", languageOf(id) == "zh", chip)
        }
        rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag("space_language_${order.first { languageOf(it) == "zh" }}"))
        assertEquals("中文", textOf("space_language_${order.first { languageOf(it) == "zh" }}"))
    }

    @Test fun journey9_in_chinese_the_chinese_spaces_come_first_and_the_english_ones_say_English() {
        RuntimeEnvironment.setQualifiers("+zh")
        launch(); chooseGuest()
        val order = spaceOrder()
        val languages = order.map(::languageOf)
        assertEquals(languages.sortedBy { it != "zh" }, languages)
        val english = order.first { languageOf(it) == "en" }
        rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag("space_language_$english"))
        assertEquals("English", textOf("space_language_$english"))
        assertFalse(exists(tagPrefix("space_language_${order.first()}")))
    }

    @Test fun journey9_counts_agree_with_their_numbers_in_both_languages() {
        // One material, two materials, one activity, many: "1 material", "2 materials", "1 activity".
        assertEquals("1 material", plural(R.plurals.spaces_material_count, 1, 1))
        assertEquals("2 materials", plural(R.plurals.spaces_material_count, 2, 2))
        assertEquals("0 / 1 activity complete", plural(R.plurals.spaces_activities_complete, 1, 0, 1))
        assertEquals("1 / 2 activities complete", plural(R.plurals.spaces_activities_complete, 2, 1, 2))
        assertEquals("1 completed activity · 1 learning day", completedDays(1, 1))
        assertEquals("2 completed activities · 3 learning days", completedDays(2, 3))
        assertEquals("1 character", plural(R.plurals.import_chars, 1, 1))
        // On screen: a Space with exactly one material says "1 material", one with two says "2 materials".
        val (one, two) = awaitIo {
            deps.profiles.choose(com.anything2game.app.data.profile.LocalProfile.GUEST)
            val spaces = deps.spaces
            val a = spaces.create("One doc").spaceId; spaces.add(a, SpaceMember.ServerDocumentRef("doc-a", "1"))
            val b = spaces.create("Two docs").spaceId
            spaces.add(b, SpaceMember.ServerDocumentRef("doc-b", "1")); spaces.add(b, SpaceMember.ServerDocumentRef("doc-c", "1"))
            a to b
        }
        launch()
        scrollHomeTo("space_counts_$one")
        assertEquals(spacesCounts(1, 0, 0), textOf("space_counts_$one")); assertTrue(textOf("space_counts_$one").startsWith("1 material · "))
        scrollHomeTo("space_counts_$two")
        assertTrue(textOf("space_counts_$two").startsWith("2 materials · "))
    }

    @Test fun journey9_the_chinese_counts_keep_their_form() {
        RuntimeEnvironment.setQualifiers("+zh")
        assertEquals("1 份资料", plural(R.plurals.spaces_material_count, 1, 1))
        assertEquals("2 份资料", plural(R.plurals.spaces_material_count, 2, 2))
        assertEquals("已完成 1 / 1 项活动", plural(R.plurals.spaces_activities_complete, 1, 1, 1))
        assertEquals("已完成 1 个活动 · 学习 1 天", completedDays(1, 1))
    }

    @Test fun journey9_a_new_space_opens_on_its_route_tab_with_the_empty_state_and_its_primary_action() {
        launch(); chooseGuest()
        waitFor("create available") { runCatching { rule.onNodeWithTag("space_create").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("space_create"))
        rule.onNodeWithTag("space_name").performTextInput("Chemistry")
        tap(rule.onNodeWithTag("save_space"))
        waitForTag("route_empty"); waitForTag("route_add_materials")
        assertFalse(has("space_materials"))
        rule.onNodeWithTag("show_route").assertIsSelected(); rule.onNodeWithTag("show_materials").assertIsNotSelected()
        tap(rule.onNodeWithTag("route_add_materials")); waitForTag("space_materials")
    }

    @Test fun journey9_the_progress_strip_stays_visible_while_the_map_scrolls() {
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        openSpaceCard(spaceIdOf(attention))
        val done = doneCount(nodes)
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_start_bubble")); rule.waitForIdle()
        // Opened at the learner's unit, scrolled past the top of the page: the strip is still in view.
        rule.onNodeWithTag("route_progress").assertIsDisplayed()
        assertEquals(str(R.string.route_progress_done, done, nodes.size), rule.onNodeWithTag("route_progress_text", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""))
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_${nodes.last().nodeId}")); rule.waitForIdle()
        rule.onNodeWithTag("route_progress").assertIsDisplayed()
        rule.onNodeWithTag("show_route").assertIsDisplayed()
        assertTrue("the learning days are in the strip", exists(hasText(str(R.string.spaces_week, 0).substringBefore("·").trim(), substring = true)))
    }

    @Test fun journey9_playground_tiles_lead_with_the_concept_and_every_row_has_one_height() {
        playground(); shelfLoaded()
        fun tileTags() = rule.onAllNodes(SemanticsMatcher("tile") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("game_") && it != "game_board" } == true }, useUnmergedTree = false).fetchSemanticsNodes()
        val tiles = tileTags()
        assertEquals(10, tiles.size)
        for (row in tiles.groupBy { Math.round(it.boundsInRoot.top) }.values) assertEquals("one height per row", 1, row.map { Math.round(it.boundsInRoot.height) }.toSet().size)
        // The concept is the primary chip; how it plays is small secondary text.
        val chip = rule.onNode(hasTestTag("concept_chip") and hasAnyAncestor(hasTestTag("game_partition")), useUnmergedTree = true)
        assertEquals("Binary search", chip.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""))
        val learn = rule.onNode(hasTestTag("learn_line") and hasAnyAncestor(hasTestTag("game_allocation")), useUnmergedTree = true)
        assertEquals(ReviewedGames.catalog.first { it.key == "workshop" }.spec("en").learn, learn.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""))
        val allocationChip = rule.onNode(hasTestTag("concept_chip") and hasAnyAncestor(hasTestTag("game_allocation")), useUnmergedTree = true)
        assertEquals("Opportunity cost", allocationChip.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""))
        assertTrue("the concept chip comes before the line about what you learn", chip.fetchSemanticsNode().boundsInRoot.top < rule.onNode(hasTestTag("learn_line") and hasAnyAncestor(hasTestTag("game_partition")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun journey9_the_demo_badge_is_on_the_result_and_not_in_the_add_sheet_header() {
        playground(); shelfLoaded()
        tap(rule.onNodeWithTag("add_game")); waitForTag("add_topic")
        assertFalse("no badge before anything was generated", textShown(str(R.string.games_demo_badge)))
        rule.onNodeWithTag("add_topic").performTextInput("orbital dynamics")
        tap(rule.onNodeWithTag("add_generate"))
        waitForTag("chooser"); waitForTag("result_demo_badge")
        assertTrue(textShown(str(R.string.games_demo_badge)))
    }

    @Test fun journey9_font_scale_two_keeps_the_nodes_of_the_map_apart() {
        RuntimeEnvironment.setFontScale(2f)
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        openSpaceCard(spaceIdOf(attention))
        nodes.forEach { n ->
            desc(n.nodeId)
            val boxes = rule.onAllNodes(tagPrefix("route_node_")).fetchSemanticsNodes().map { it.boundsInRoot }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("nodes overlap: ${boxes[i]} ${boxes[j]}", boxes[i].overlaps(boxes[j]))
            val labels = rule.onAllNodes(tagPrefix("route_label_")).fetchSemanticsNodes().map { it.boundsInRoot }
            for (i in labels.indices) for (j in i + 1 until labels.size) assertFalse("labels overlap: ${labels[i]} ${labels[j]}", labels[i].overlaps(labels[j]))
            for (b in boxes) for (l in labels) assertFalse("a label sits on a node: $b $l", b.overlaps(l.copy(top = l.top + 1f, bottom = l.bottom - 1f)) && l.top > b.top + 1f && l.bottom < b.bottom)
        }
    }
}
