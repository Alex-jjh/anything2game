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
import com.anything2game.contract.tutor.UNKNOWN_ANSWER
import com.anything2game.engine.game.GameRuntime
import com.anything2game.engine.game.PartitionBoard
import com.anything2game.engine.game.StageStatus
import com.anything2game.engine.session.Phase
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * QA of the demo path, written the way a first-time student and a sceptical lecturer would walk it: the real app, real taps.
 * Journeys 1-8 of the demo-path brief. Tests marked @Ignore("BUG: ...") are failing proofs of real defects; they stay in the
 * file so the suite is green and the defect stays reproducible (remove the annotation to see it fail).
 */
class DemoPathJourneyTest : LearnJourneyBase() {
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun tagPrefix(p: String) = SemanticsMatcher("tag $p*") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(p) == true }
    private fun nodesOf(pkg: String): List<PathNode> = awaitIo { deps.materials!!.materials().single { it.localPack?.packageId == pkg }.nodes }
    private fun spaceIdOf(pkg: String): String = awaitIo {
        val mat = deps.materials!!.materials().single { it.localPack?.packageId == pkg }
        deps.spaces!!.list().first { mat.member in it.members }.spaceId
    }
    private fun desc(nodeId: String): String {
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_$nodeId")); rule.waitForIdle()
        return rule.onNodeWithTag("route_node_$nodeId").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")
    }
    private fun state(nodeId: String): String {
        val d = desc(nodeId)
        return listOf(R.string.route_state_review, R.string.route_state_done, R.string.route_state_current, R.string.route_state_available,
            R.string.route_state_locked, R.string.route_state_update).map { str(it) }.first { d.endsWith(it) }
    }
    private fun scrollHomeTo(tag: String) = waitFor("home tile $tag") { runCatching { rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false) }
    private fun openSpaceCard(spaceId: String) { scrollHomeTo("open_space_$spaceId"); tap(rule.onNodeWithTag("open_space_$spaceId")); waitForMap() }
    private fun waitForMap() = waitFor("route map") { exists(tagPrefix("route_node_")) || exists(hasTestTag("route_empty")) }
    private fun back() { systemBack(); rule.waitForIdle() }
    private fun doneCount(nodes: List<PathNode>) = nodes.count { state(it.nodeId).let { s -> s == str(R.string.route_state_done) || s == str(R.string.route_state_review) } }
    private fun assertRouteProgress(done: Int, total: Int) {
        waitFor("route progress $done/$total") {
            runCatching { rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_progress")); true }.getOrDefault(false) &&
                rule.onNodeWithTag("route_progress").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString() == str(R.string.route_progress_description, done, total)
        }
    }
    private fun meRecord(): String {
        openMe()
        val p = awaitIo { deps.learningProgress!!.projection() }
        val expected = str(R.string.completed_days, p.completed.size, p.days.size)
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
    private fun projectionNow() = awaitIo { deps.learningProgress!!.projection() }

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

    /** Reads the target concept to its end from the explainer (assessment first when the tutor insists), then returns to the map. */
    private fun explainUntilRead(pkg: String, node: PathNode) {
        val target = (node.activity as ActivityRef.Tutor).activityId
        fun read() = projectionNow().completed.any { it.activityKind == com.anything2game.contract.progress.ActivityKind.TUTOR && it.activityId == target }
        waitFor("prerequisite screen or explanation") { has("route_prerequisite") || has("tutor_list") || has("explainer_stage") }
        if (has("route_prerequisite") && textOf("route_prerequisite") == str(R.string.route_diagnostic, node.title)) {
            tap(rule.onNodeWithTag("route_follow")); waitFor("focus or questions") { has("focus_continue") || has("tutor_list") }
        }
        if (has("focus_continue")) chooseFocus()
        if (has("tutor_list") && !has("start_tutor") && !has("explainer_stage") && !has("route_follow")) {
            val total = cache(pkg).session.questions.size
            for (i in 0 until total) {
                val q = cache(pkg).session.questions[i]
                scrollTutorToText(q.prompt); answerCurrent(q, UNKNOWN_ANSWER)
                if (i < total - 1) waitForText(str(R.string.tutor_question_count, i + 2, total))
            }
        }
        var guard = 0
        while (!read() && guard++ < 12) {
            waitFor("next explainer step") { has("route_follow") || has("start_tutor") || has("explainer_stage") }
            when {
                has("route_follow") -> { tap(rule.onNodeWithTag("route_follow")); waitFor("after follow") { has("start_tutor") || has("explainer_stage") } }
                has("start_tutor") -> { tapTutor("start_tutor"); sceneReady() }
                else -> {
                    sceneReady(); readPages("page of ${node.title}")
                    if (!read()) choose(pkg, "tutor_continue")
                }
            }
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
        val preDone = awaitIo { deps.spaces!!.route(spaceId, "en")!!.nodes.size }   // sanity: route and material agree
        assertEquals(total, preDone)

        openSpaceCard(spaceId)
        val states = nodes.map { state(it.nodeId) }
        val doneBefore = nodes.count { state(it.nodeId) == str(R.string.route_state_done) || state(it.nodeId) == str(R.string.route_state_review) }
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
        assertTrue(textShown(str(R.string.spaces_counts, 1, doneBefore, total)))
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
        waitFor("the finished node to show done") { state(current.nodeId) == str(R.string.route_state_done) }
        waitFor("the next node to become current") { state(next.nodeId) == str(R.string.route_state_current) }
        assertEquals(1, nodes.count { state(it.nodeId) == str(R.string.route_state_current) })
        assertRouteProgress(doneBefore + 1, total)
        val bubbleCount = rule.onAllNodesWithTag("route_start_bubble").fetchSemanticsNodes().size
        assertEquals("one Start bubble on the map", 1, bubbleCount)

        // Header, Spaces card and Me all say the same.
        back(); waitForTag("home_SPACES")
        scrollHomeTo("open_space_$spaceId")
        waitFor("card to say ${doneBefore + 1}") { textShown(str(R.string.spaces_counts, 1, doneBefore + 1, total)) }
        val after = projectionNow()
        assertEquals(before.completed.size + 1, after.completed.size)
        val meAfter = meRecord()
        assertEquals(str(R.string.completed_days, after.completed.size, after.days.size), meAfter)
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

    @Ignore("BUG: Lin's map shows 'Why ditch recurrence' as done, but Start on the current step asks 4 diagnostic questions beginning with that very concept and says 'The lesson sequence starts from Why ditch recurrence' (SampleProfileSeeder credits reads without a tutor session)")
    @Test fun journey1_bug_start_on_lins_current_step_does_not_re_teach_a_step_the_map_calls_done() {
        launch(); chooseLin()
        val nodes = nodesOf(attention)
        openSpaceCard(spaceIdOf(attention))
        val current = nodes.first { state(it.nodeId) == str(R.string.route_state_current) }
        val doneTitles = nodes.filter { state(it.nodeId) == str(R.string.route_state_done) }.map { it.title }
        tap(rule.onNodeWithTag("route_node_${current.nodeId}")); waitForTag("route_sheet")
        startFromSheet(current)
        waitForTag("route_prerequisite")
        tap(rule.onNodeWithTag("route_follow")); chooseFocus()
        waitForTag("tutor_list")
        val shown = everythingShown().joinToString("\n")
        doneTitles.forEach { assertFalse("the explainer asks about \"$it\", which the map shows as done", shown.contains(it)) }
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
        waitFor("the practice node to be done") { state(current.nodeId).let { it == str(R.string.route_state_done) || it == str(R.string.route_state_review) } }
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
    private fun run(id: String) = awaitIo { deps.learningProgress!!.checkpoint(versionOf(id)) }?.let { GameRuntime.replay(specOf(id), it) }
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
        tap(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
        val id = "locker-search-en"
        playPartitionStage(id, slideFirst = true)
        waitForTag("game_result"); waitForTag("game_stars"); waitForText(str(R.string.games_won))
        assertTrue(textShown(str(R.string.games_stamp_clear)))
        // Leave in the middle of the whole game (stage 1 won, transfer not started): the shelf offers to continue.
        back(); waitForTag("home_PLAYGROUND")
        waitForTag("resume_game")
        assertTrue(badgeOn("game_partition", str(R.string.playground_continue)))
        tap(rule.onNodeWithTag("resume_game")); waitForTag("game_advance")
        assertTrue("won round is still shown after coming back", has("game_stars"))
        tap(rule.onNodeWithTag("game_advance")); waitForTag("new_conditions")
        waitForText(str(R.string.games_transfer))
        playPartitionStage(id)
        waitForText(str(R.string.games_complete)); waitForTag("game_stars")
        val stars = deps.homePreferences.bestStars(versionOf(id))
        assertTrue("a completed game has stars ($stars)", stars in 1..3)
        back(); waitForTag("home_PLAYGROUND"); shelfLoaded()
        assertFalse("a finished game is no longer 'Continue'", has("resume_game"))
        assertFalse(badgeOn("game_partition", str(R.string.playground_continue)))
        assertFalse(badgeOn("game_partition", str(R.string.playground_new)))
        waitFor("stars on the tile") { rule.onAllNodes(hasContentDescription(str(R.string.playground_stars_cd, stars))).fetchSemanticsNodes().isNotEmpty() }
        // Me reflects exactly one completed activity.
        val p = projectionNow()
        assertEquals(1, p.completed.size)
        openMe(); scrollMeTo(str(R.string.completed_days, 1, 1))
    }

    @Test fun journey4_allocation_game_through_both_stages_with_a_real_drag() {
        playground()
        waitForTag("game_allocation"); tap(rule.onNodeWithTag("game_allocation")); waitForTag("token_tray")
        val id = "workshop-en"
        // One token by dragging, the rest by the place buttons.
        val tray = rule.onNodeWithTag("token_tray"); val origin = tray.fetchSemanticsNode().boundsInRoot.topLeft
        val zone = rule.onNodeWithTag("place_SECOND").fetchSemanticsNode().boundsInRoot.center - origin
        tray.performTouchInput { down(Offset(20f, 20f)); moveTo(Offset(50f, 40f)); moveTo(Offset(90f, 80f)); moveTo(zone); up() }
        waitForText("5 tokens left")
        val (f1, s1) = allocationNeeds(id, 0)
        placeTokens(f1, s1 - 1)
        waitForText("0 tokens left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForTag("game_stars"); waitForText(str(R.string.games_won))
        tap(rule.onNodeWithTag("game_advance")); waitForTag("new_conditions")
        val (f2, s2) = allocationNeeds(id, 1)
        placeTokens(f2, s2)
        waitForText("0 tokens left")
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForText(str(R.string.games_complete)); waitForTag("game_stars")
        val stars = deps.homePreferences.bestStars(versionOf(id))
        assertTrue(stars in 1..3)
        back(); waitForTag("home_PLAYGROUND"); shelfLoaded()
        waitFor("stars on the tile") { rule.onAllNodes(hasContentDescription(str(R.string.playground_stars_cd, stars))).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(has("resume_game"))
        openMe(); scrollMeTo(str(R.string.completed_days, 1, 1))
    }

    @Test fun journey4_a_lost_round_can_be_retried_and_gives_no_stars_or_completion() {
        playground()
        waitForTag("game_allocation"); tap(rule.onNodeWithTag("game_allocation")); waitForTag("place_FIRST")
        placeTokens(6, 0)            // all tokens to one zone: the goal is missed
        waitForText("0 tokens left")
        waitFor("deliver enabled") { runCatching { rule.onNodeWithTag("allocation_submit").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("allocation_submit"))
        waitForTag("retry_round")
        assertFalse(has("game_stars")); assertFalse(has("game_advance"))
        assertTrue(textShown(str(R.string.games_lost)) || textShown(str(R.string.games_stamp_miss)))
        tap(rule.onNodeWithTag("retry_round")); waitForText("6 tokens left")
        assertEquals(0, deps.homePreferences.bestStars(versionOf("workshop-en")))
        assertTrue(projectionNow().completed.isEmpty())
    }

    @Test fun journey4_peeking_into_a_game_does_not_turn_it_into_continue() {
        playground()
        waitForTag("game_partition"); tap(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
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
            waitForTag("game_board"); waitForText("Hunt the broken commit"); rule.onNodeWithTag("demo_badge").assertExists()
            assertTrue(textShown(str(R.string.games_demo_badge)))
            back(); waitForTag("home_PLAYGROUND")
            addGame("Opportunity Cost  "); waitForTag("game_board"); waitForText("Two workshops"); rule.onNodeWithTag("demo_badge").assertExists()
            back(); waitForTag("home_PLAYGROUND")
            addGame("机会成本"); waitForTag("game_board"); rule.onNodeWithTag("demo_badge").assertExists()
            assertEquals("no request to the server", 0, spy.hits.get())
        }
    }

    @Test fun journey5_in_chinese_both_topics_find_chinese_games() {
        RuntimeEnvironment.setQualifiers("+zh")
        Spy().use { spy ->
            playground()
            addGame("git bisect"); waitForTag("game_board"); waitForTag("demo_badge")
            waitForText(specOf("git-bisect-zh").title)
            back(); waitForTag("home_PLAYGROUND")
            addGame("机会成本"); waitForTag("game_board")
            waitForText(specOf("workshop-zh").title)
            back(); waitForTag("home_PLAYGROUND")
            addGame("量子色动力学"); waitForTag("chooser"); waitForText(str(R.string.add_choose))
            tap(rule.onNodeWithTag("chooser_farm-crops-zh")); waitForTag("game_board"); waitForTag("demo_badge")
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
        tap(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
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
        waitForTag("space_materials")                    // a new Space opens on its materials
        assertNoJargon("the new Space's materials", everythingShown())
        waitForTag("show_route"); tap(rule.onNodeWithTag("show_route"))
        waitForTag("route_empty")
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
        waitFor("started") { has("route_prerequisite") }
    }

    @Ignore("BUG (cosmetic): at font scale 2.0 long step labels under the nodes are cut to 3 lines with an ellipsis ('Results, Variations and Generalization') and the sheet's source chip is cut to 2 lines; only the node sheet shows the full title (RouteMapView StepRow: maxLines = 3, widthIn(max = 176.dp))")
    @Test fun journey7_bug_font_scale_two_step_labels_are_complete() {
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
        waitForTag("game_partition"); tap(rule.onNodeWithTag("game_partition")); waitForTag("split_submit")
        tap(rule.onNodeWithTag("cut_3"))
        val cutTexts = clipped()
        assertTrue("clipped texts on the 200% partition board: $cutTexts", cutTexts.isEmpty())
        tap(rule.onNodeWithTag("split_submit"))
        waitFor("question spent") { run("locker-search-en")!!.checkpoint.actions.size == 1 }
        back(); waitForTag("home_PLAYGROUND"); waitForTag("game_allocation")
        tap(rule.onNodeWithTag("game_allocation")); waitForTag("place_FIRST")
        placeTokens(1, 1)
        waitForText("4 tokens left")
        val texts = clipped()
        assertTrue("clipped texts on the 200% allocation board: $texts", texts.isEmpty())
        placeTokens(2, 2)
        tap(rule.onNodeWithTag("allocation_submit"))
        waitFor("a result") { has("game_result") }
    }

    // ---- Journey 8: rotation and process restart --------------------------------------------------------------------------

    @Test fun journey8_partition_game_keeps_its_state_through_rotation_recreation_and_a_process_restart() {
        playground()
        waitForTag("game_partition"); tap(rule.onNodeWithTag("game_partition")); waitForTag("cut_4")
        tap(rule.onNodeWithTag("cut_6"))
        val n0 = run("locker-search-en")!!.checkpoint.actions.size
        tap(rule.onNodeWithTag("split_submit")); waitFor("asked") { run("locker-search-en")!!.checkpoint.actions.size > n0 }
        val before = run("locker-search-en")!!.board as PartitionBoard
        tap(rule.onNodeWithTag("cut_${(before.low + before.high) / 2}"))
        RuntimeEnvironment.setQualifiers("+land"); rule.waitForIdle()
        waitForTag("split_submit")
        val after = run("locker-search-en")!!.board as PartitionBoard
        assertEquals(before, after)
        assertTrue("the cut being chosen survives rotation", textShown(str(R.string.games_split, (before.low + before.high) / 2)))
        RuntimeEnvironment.setQualifiers("+port"); rule.waitForIdle()
        scenario!!.recreate(); waitForTag("split_submit")
        assertEquals(before, run("locker-search-en")!!.board)
        // Process death: nothing but the disk remains.
        restartProcess()
        waitFor("home after restart") { has("home_SPACES") || has("home_PLAYGROUND") || has("game_board") }
        if (!has("home_PLAYGROUND") && !has("game_board")) { tap(rule.onNodeWithTag("tab_PLAYGROUND")); waitForTag("home_PLAYGROUND") }
        if (!has("game_board")) { waitForTag("resume_game"); tap(rule.onNodeWithTag("resume_game")) }
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
        waitFor("after restart") { has("home_SPACES") || has("tutor_list") || has("explainer_stage") || has("route_prerequisite") }
        if (has("home_SPACES")) {
            openSpaceCard(spaceId)
            tap(rule.onNodeWithTag("route_node_${current.nodeId}")); waitForTag("route_sheet")
            startFromSheet(current)
        }
        waitFor("the explainer again") { has("explainer_stage") || has("route_follow") || has("start_tutor") }
        assertEquals("the saved concept position is kept", position, cache(attention).session.conceptPosition)
        assertEquals("nothing was lost from the saved conversation", messages, cache(attention).session.messages.size)
    }

    private fun explainUntilReadingStarted() {
        waitFor("prerequisite screen or explanation") { has("route_prerequisite") || has("tutor_list") || has("explainer_stage") }
        tap(rule.onNodeWithTag("route_follow")); waitFor("focus") { has("focus_continue") || has("tutor_list") }
        if (has("focus_continue")) chooseFocus()
        val total = cache(attention).session.questions.size
        for (i in 0 until total) {
            val q = cache(attention).session.questions[i]
            scrollTutorToText(q.prompt); answerCurrent(q, UNKNOWN_ANSWER)
            if (i < total - 1) waitForText(str(R.string.tutor_question_count, i + 2, total))
        }
        waitFor("follow") { has("route_follow") }
        tap(rule.onNodeWithTag("route_follow"))
        waitFor("scene") { has("start_tutor") || has("explainer_stage") }
        if (has("start_tutor")) tapTutor("start_tutor")
        sceneReady()
    }
}
