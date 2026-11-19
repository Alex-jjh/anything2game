package com.anything2game.app.journeys

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import com.anything2game.app.A2gApplication
import com.anything2game.app.AppDependencies
import com.anything2game.app.MainActivity
import com.anything2game.app.R
import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.FrameId
import com.anything2game.contract.Item
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.demo.ScriptedPlayer
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.CrossExamAnswer
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.OrderAnswer
import com.anything2game.engine.grading.SortAnswer
import com.anything2game.engine.grading.SwipeAnswer
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base for journey tests: the REAL MainActivity, AppRoot and AndroidAppContainer (real file stores, real preset packs),
 * driven only by taps and swipes on what a student sees. The tests read the session on disk to know *which* answer to
 * give (the item data), never to change anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class JourneyTest {
    @get:Rule val rule = createEmptyComposeRule()
    protected var scenario: ActivityScenario<MainActivity>? = null
    private var activeLevel: Level? = null
    protected val app: A2gApplication get() = RuntimeEnvironment.getApplication() as A2gApplication
    protected val deps: AppDependencies get() = app.dependencies

    @Before fun animationsOff() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeActivity() { runCatching { scenario?.close() } }

    protected fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    protected fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    /** A process restart: the activity goes away, the container (and every in-memory view model) is rebuilt from disk. */
    protected fun restartProcess() {
        scenario?.close()
        val field = A2gApplication::class.java.getDeclaredField("dependencies\$delegate")
        field.isAccessible = true
        field.set(app, lazy { com.anything2game.app.AndroidAppContainer(app) })
        launch()
    }

    // ---- waiting -----------------------------------------------------------------------------------------

    protected fun waitFor(what: String = "condition", timeoutMs: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(timeoutMs) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); cond() } } catch (e: Throwable) {
            runCatching { java.io.File(System.getProperty("java.io.tmpdir"), "a2g-journey-dump.txt").writeText("waiting for $what\n" + rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size + "\n" + rule.onRoot().printToString(200)) }
            throw AssertionError("Waiting for $what failed (disk: ${runCatching { activeLevel?.here() }.getOrNull()}): ${e::class.simpleName}: ${e.message}", e)
        }
    }
    protected fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    protected fun textShown(t: String) = rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
    protected fun waitForText(t: String, timeoutMs: Long = 30_000) = waitFor("text \"$t\"", timeoutMs) { textShown(t) }
    protected fun waitForTag(tag: String) = waitFor("tag $tag") { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Click anything a finger could click; scroll it into view first when it sits in a scrolling column. */
    protected fun tap(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        revealAboveNavigation(node)
        node.performClick()
        rule.waitForIdle()
    }
    /** A finger cannot press a button that sits under the bottom bar: drag the list up, as a student would, until it is clear. */
    private fun revealAboveNavigation(node: SemanticsNodeInteraction) {
        if (node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Tab) return
        val list = listOf("home_PRACTICE", "home_PROFILE", "home_LEARN").firstOrNull { rule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() } ?: return
        val navTop = rule.onAllNodesWithTag("home_navigation").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top ?: return
        repeat(8) {
            val b = node.fetchSemanticsNode().boundsInRoot
            if (b.center.y < navTop - 40f && b.center.y > 0f) return
            // Not placed yet (an unlaid-out lazy item has empty bounds): it is further down the list.
            val down = b.height == 0f || b.center.y >= navTop - 40f
            rule.onNodeWithTag(list).performTouchInput {
                val x = centerX; val y0 = if (down) height * 0.7f else height * 0.3f
                swipe(Offset(x, y0), Offset(x, if (down) y0 - 400f else y0 + 400f), 200)
            }
            rule.waitForIdle()
        }
        val b = node.fetchSemanticsNode().boundsInRoot
        throw AssertionError("Cannot bring the control above the bottom bar: $b vs navigation top $navTop")
    }
    protected fun tapText(t: String) {
        waitForText(t)
        tap(rule.onNode(hasText(t) and hasClickAction()).let { n -> if (exists(hasText(t) and hasClickAction())) n else rule.onNode(hasText(t)) })
    }
    protected fun systemBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    // ---- welcome / home ----------------------------------------------------------------------------------

    protected fun chooseLin() { waitForTag("choose_sample"); tap(rule.onNodeWithTag("choose_sample")); waitForTag("tab_PRACTICE") }
    protected fun chooseGuest() { waitForTag("choose_guest"); tap(rule.onNodeWithTag("choose_guest")); waitForTag("tab_PRACTICE") }
    protected fun openPractice() { tap(rule.onNodeWithTag("tab_PRACTICE")); waitForTag("home_PRACTICE") }
    protected fun openMe() { tap(rule.onNodeWithTag("tab_PROFILE")); waitForTag("home_PROFILE") }
    protected fun switchProfile() { openMe(); tapText(str(R.string.profile_switch)); waitForTag("welcome") }

    /** Scrolls a Practice / Me list until [text] is on screen (list items compose lazily); waits while the library loads. */
    private fun scrollListTo(tag: String, text: String) {
        var last: Throwable? = null
        repeat(60) {
            try {
                rule.onNodeWithTag(tag).performScrollToNode(hasText(text))
                rule.waitForIdle()
                return
            } catch (e: AssertionError) { last = e; rule.waitForIdle(); Thread.sleep(100) }
        }
        throw AssertionError("Could not scroll to \"$text\" in $tag", last)
    }
    protected fun scrollPracticeTo(text: String) = scrollListTo("home_PRACTICE", text)
    protected fun scrollMeTo(text: String) = scrollListTo("home_PROFILE", text)

    /** The button of one level in Practice: the first "Start practice" / "Continue your session" below the level's title. */
    protected fun levelButton(courseTitle: String, levelTitle: String): SemanticsNodeInteraction {
        scrollPracticeTo(courseTitle)
        scrollPracticeTo(levelTitle)
        val titleTop = rule.onNode(hasText(levelTitle)).fetchSemanticsNode().boundsInRoot.top
        val buttons = rule.onAllNodes(hasClickAction() and (hasText(str(R.string.home_practice_action)) or hasText(str(R.string.home_resume))))
        val idx = buttons.fetchSemanticsNodes().withIndex().filter { it.value.boundsInRoot.top > titleTop }.minByOrNull { it.value.boundsInRoot.top }?.index
            ?: throw AssertionError("No start button under level \"$levelTitle\"")
        return buttons[idx]
    }
    /** What Practice says about one level ("Needs practice", "New", "In progress · 3/9"…). */
    protected fun levelStatus(courseTitle: String, levelTitle: String): String {
        scrollPracticeTo(courseTitle); scrollPracticeTo(levelTitle)
        // The status line is the Text that follows the level title inside the card.
        val titleTop = rule.onNode(hasText(levelTitle)).fetchSemanticsNode().boundsInRoot.top
        val texts = rule.onAllNodes(SemanticsMatcher("text below title") { it.config.getOrNull(SemanticsProperties.Text) != null })
            .fetchSemanticsNodes().filter { it.boundsInRoot.top > titleTop }.sortedBy { it.boundsInRoot.top }
        return texts.first().config[SemanticsProperties.Text].joinToString("") { it.text }
    }
    /** Click "Play as <frame>" on one course card. */
    protected fun playAs(courseTitle: String, frame: FrameId) {
        scrollPracticeTo(courseTitle)
        val titleTop = rule.onNode(hasText(courseTitle)).fetchSemanticsNode().boundsInRoot.top
        val chips = rule.onAllNodes(hasTestTag("play_as_${frame.name}"))
        val idx = chips.fetchSemanticsNodes().withIndex().filter { it.value.boundsInRoot.top > titleTop }.minByOrNull { it.value.boundsInRoot.top }?.index
            ?: throw AssertionError("No \"Play as $frame\" chip under $courseTitle")
        tap(chips[idx])
        // The choice is saved and the library redrawn asynchronously; a student sees the chip light up before tapping Start.
        waitFor("the $frame chip to be selected") {
            rule.onAllNodes(hasTestTag("play_as_${frame.name}")).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true && it.boundsInRoot.top > titleTop - 1f }
        }
    }
    protected fun startLevel(courseTitle: String, levelTitle: String) {
        tap(levelButton(courseTitle, levelTitle))
        waitFor("play screen") { exists(hasContentDescription(str(R.string.action_back))) }
    }

    /** A number on the Me tab ("Documents", "Practiced units", "Due for review"): the figure printed right above its label. */
    protected fun stat(@StringRes label: Int): Int {
        val l = str(label)
        // Scrolling a label into view can leave it on the very top edge, where the figure printed above it is clipped away
        // (an empty rectangle): a student drags the list down a little, and so does this. Also tolerates the card being redrawn while the library loads.
        fun figure(): Int? {
            val labelNode = rule.onNode(hasText(l)).fetchSemanticsNode().boundsInRoot
            val numbers = rule.onAllNodes(SemanticsMatcher("number") { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.toIntOrNull() != null })
                .fetchSemanticsNodes().filter { it.boundsInRoot.bottom <= labelNode.top + 1f && kotlin.math.abs(it.boundsInRoot.left - labelNode.left) < 4f }
            return numbers.maxByOrNull { it.boundsInRoot.bottom }?.config?.get(SemanticsProperties.Text)?.first()?.text?.toInt()
        }
        var last: Throwable? = null
        repeat(20) {
            try {
                scrollMeTo(l)
                figure()?.let { return it }
                rule.onNodeWithTag("home_PROFILE").performTouchInput { swipe(Offset(centerX, centerY - 150f), Offset(centerX, centerY + 150f), 150) }
                rule.waitForIdle()
                figure()?.let { return it }
                last = AssertionError("No figure above \"$l\"")
            } catch (e: AssertionError) { last = e }
            rule.waitForIdle(); Thread.sleep(150)
        }
        throw last!!
    }

    // ---- playing -----------------------------------------------------------------------------------------

    /** One level of one course in one frame, played with real gestures; progress is read back from the session file. */
    protected inner class Level(val packageId: String, val frame: FrameId) {
        val pkg: LearningPackage = runBlocking { deps.packages.load(packageId).getOrThrow() }
        val overlay = runBlocking { deps.frames.load(packageId, frame) }
        val engine = LevelSession(pkg, deps.grading, deps.masteryPolicy, deps.clock, frame = Frames.of(frame), overlay = overlay)

        init { activeLevel = this }
        fun session(): SessionState? = runBlocking { deps.sessions.load(packageId) }
        fun mastery(): Map<String, MasteryRecord> = runBlocking { deps.mastery.all(packageId) }
        fun item(s: SessionState): Item? = engine.item(s)

        /** The same step the student is looking at now. */
        fun here(): String = session()!!.let { "${it.levelId}#${it.position}/${it.queue.size}/${it.phase::class.simpleName}" }

        /** Does the one thing a student would do on the current screen. Returns false when the level is over. */
        fun step(policy: (answered: Int, item: Item) -> Boolean = { _, _ -> true }): Boolean {
            val s = session() ?: throw AssertionError("no session on disk for $packageId")
            if (s.isFinished) return false
            when (val phase = s.phase) {
                is Phase.Feedback -> {
                    val last = s.position + 1 >= s.queue.size
                    val label = str(if (last) R.string.action_finish else R.string.action_next)
                    waitFor("\"$label\" button") { exists(hasText(label) and hasClickAction()) }
                    tap(rule.onNode(hasText(label) and hasClickAction()))
                    waitFor("next step after ${s.position}") { session()!!.let { it.position != s.position || it.isFinished } }
                }
                Phase.Presenting -> {
                    val item = engine.item(s)
                    if (item == null) tapScene(s) else {
                        val right = policy(s.results.size, item)
                        val answer = if (right) ScriptedPlayer.correctAnswer(item) else ScriptedPlayer.wrongAnswer(item)
                        perform(item, answer)
                        waitFor("feedback for answer ${s.results.size} (${item::class.simpleName})") { session()!!.results.size > s.results.size }
                    }
                }
                Phase.Recap, Phase.Failed -> return false
            }
            return true
        }

        /** Plays [steps] more steps (or until the level ends); returns whether it is still open. */
        fun play(steps: Int = Int.MAX_VALUE, policy: (Int, Item) -> Boolean = { _, _ -> true }): Boolean {
            var n = 0
            while (n++ < steps) if (!step(policy)) return false
            return true
        }
        fun playUntil(what: String, policy: (Int, Item) -> Boolean = { _, _ -> true }, stop: (SessionState) -> Boolean) {
            var guard = 0
            while (guard++ < 200) {
                val s = session()!!
                if (stop(s)) return
                if (!step(policy)) throw AssertionError("level ended before: $what")
            }
            throw AssertionError("never reached: $what")
        }

        private fun tapScene(s: SessionState) {
            val lines = engine.sceneLines(s).orEmpty()
            check(lines.isNotEmpty()) { "scene without lines at ${here()}" }
            repeat(lines.size + 1) {
                val shown = lines.indexOfFirst { l -> textShown(l.text) }
                check(shown >= 0) { "no scene line on screen at ${here()}" }
                tap(rule.onAllNodesWithText(lines[shown].text)[0])
                if (shown == lines.lastIndex) {
                    waitFor("scene to advance") { session()!!.let { it.position != s.position || it.isFinished } }
                    return
                }
            }
            throw AssertionError("scene never advanced at ${here()}")
        }

        // ---- the five mechanics, by hand ------------------------------------------------------------------

        fun perform(item: Item, answer: Answer) {
            when (item) {
                is SwipeItem -> {
                    val verdicts = (answer as SwipeAnswer).verdicts
                    item.cards.forEach { card -> swipeCard(card.statement, verdicts.getValue(card.id)) }
                }
                is SortItem -> {
                    (answer as SortAnswer).placements.forEach { (cardId, binId) ->
                        val card = item.cards.first { it.id == cardId }
                        tap(rule.onNode(hasText(card.text) and hasClickAction()))
                        // Tap the bin's own title (not its middle, which may be a filed card).
                        tap(rule.onNode(hasText(item.bins.first { it.id == binId }.label), useUnmergedTree = true))
                    }
                    tapText(str(R.string.action_submit))
                }
                is OrderItem -> {
                    val want = (answer as OrderAnswer).stepIds.map { id -> item.steps.first { it.id == id }.text }
                    fun shown(): List<String> = item.steps.map { it.text }.sortedBy { t -> rule.onNode(hasText(t)).fetchSemanticsNode().boundsInRoot.top }
                    for (target in want.indices) {
                        while (true) {
                            val now = shown()
                            val at = now.indexOf(want[target])
                            if (at <= target) break
                            tap(rule.onAllNodesWithContentDescription(str(R.string.order_up))[at])
                        }
                    }
                    tapText(str(R.string.action_submit))
                }
                is DeduceItem -> {
                    val option = item.options.first { it.id == (answer as DeduceAnswer).optionId }
                    tap(rule.onNode(hasText(option.text)))
                    tapText(str(R.string.action_submit))
                }
                is CrossExamItem -> {
                    val a = answer as CrossExamAnswer
                    tap(rule.onNode(hasText(item.statements.first { it.id == a.statementId }.text)))
                    hold(str(R.string.crossexam_hold))
                    val quote = (item.statements.flatMap { it.sourceRefs } + pkg.levels.flatMap { l -> l.units.flatMap { u -> u.item.allSourceRefs } })
                        .firstOrNull { it.chunkId == a.chunkId && it.quote.isNotBlank() }?.quote
                    waitFor("evidence picker") { textShown(str(R.string.crossexam_collected)) }
                    val rows = rule.onAllNodes(hasClickAction() and SemanticsMatcher("evidence row") {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.startsWith("p.") } == true
                    })
                    val pick = quote?.let { q -> rule.onAllNodes(hasClickAction() and hasText(q.take(40), substring = true)) }?.takeIf { it.fetchSemanticsNodes().isNotEmpty() } ?: rows
                    tap(pick[0])
                }
                else -> error("no gesture for ${item::class.simpleName}")
            }
        }

        /** Drag the card showing [statement] past the commit line: right = supported, left = no basis. */
        fun swipeCard(statement: String, right: Boolean) {
            waitFor("card \"${statement.take(30)}\"") { textShown(statement) }
            rule.onNode(hasText(statement)).performTouchInput { swipe(Offset(centerX - 150f, centerY), Offset(centerX + if (right) 450f else -750f, centerY), 250) }
            rule.waitForIdle()
        }

        private fun hold(label: String) {
            waitForText(label)
            rule.mainClock.autoAdvance = false
            rule.onNode(hasText(label)).performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(900)
            rule.onNode(androidx.compose.ui.test.isRoot()).performTouchInput { up() }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
        }
    }
}
