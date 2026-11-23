package com.anything2game.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.geometry.Rect
import com.anything2game.app.R
import com.anything2game.app.data.tutor.TutorCache
import com.anything2game.app.ui.reading.DialoguePaging
import com.anything2game.contract.tutor.TutorConcept
import com.anything2game.contract.tutor.TutorQuestion
import com.anything2game.contract.tutor.TutorRules
import com.anything2game.contract.tutor.TutorScript
import com.anything2game.contract.tutor.UNKNOWN_ANSWER
import com.anything2game.contract.tutor.Understanding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Helpers for the Learn-tab journeys: the real app, driven by taps on what a first-time student sees.
 * The saved tutor session is read back only to know which answer to give and to check what was kept; never to change anything.
 */
abstract class LearnJourneyBase : JourneyTest() {
    protected val attention = "demo_attention"
    protected val resnet = "demo_resnet"
    protected val zhSpaced = "demo_zh_spaced"
    protected val retrieval = "pkg_demo_retrieval"

    protected fun script(pkgId: String): TutorScript = runBlocking { deps.tutorScripts.scriptFor(pkgId)!! }
    protected fun concept(pkgId: String, questionId: String): TutorConcept = script(pkgId).blueprint.concepts.first { it.question.id == questionId }
    protected fun title(pkgId: String): String = runBlocking { deps.packages.load(pkgId).getOrThrow().title }
    protected fun cache(pkgId: String): TutorCache = runBlocking { deps.tutors.open(pkgId) }.cache
    protected fun pageLabelOf(pkgId: String, chunkId: String): String =
        runBlocking { deps.packages.load(pkgId).getOrThrow().sources.first { it.chunkId == chunkId } }.let { it.pageLabel ?: (it.pageIndex + 1).toString() }

    // ---- reading what is on screen ----------------------------------------------------------------------

    protected fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    protected fun textOf(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    protected fun boundsOf(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    protected fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Every string a student can read or hear right now (text and content descriptions of every window). */
    protected fun everythingShown(): List<String> =
        rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "))
        }

    // ---- Learn tab --------------------------------------------------------------------------------------

    protected fun openLearn() = openPractice()

    protected fun scrollListTo(list: String, tag: String) {
        var last: Throwable? = null
        repeat(60) {
            try { rule.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)); rule.waitForIdle(); return } catch (e: AssertionError) { last = e; rule.waitForIdle(); Thread.sleep(100) }
        }
        throw AssertionError("Could not scroll to tag $tag in $list", last)
    }
    protected fun scrollLearnTo(tag: String) {
        openMaterial(title(tag.removePrefix("start_explanation_")))
        scrollListTo("space_materials", tag)
    }

    protected fun startExplanation(pkgId: String) {
        scrollLearnTo("start_explanation_$pkgId")
        tap(rule.onNodeWithTag("start_explanation_$pkgId"))
        waitFor("the explanation screen for $pkgId") { has("tutor_list") || has("explainer_stage") }
    }

    /** What the student sees on the card of one course in Learn: the text of its action button. */
    protected fun learnCardAction(pkgId: String): String { scrollLearnTo("start_explanation_$pkgId"); return textOf("start_explanation_$pkgId") }

    /** The shelf re-reads the saved sessions when it comes back on screen: wait until the card of [pkgId] says [label]. */
    protected fun assertLearnCard(pkgId: String, label: String) {
        scrollLearnTo("start_explanation_$pkgId")
        waitFor("the Learn card of $pkgId to say \"$label\" (says \"${runCatching { textOf("start_explanation_$pkgId") }.getOrNull()}\")") { textOf("start_explanation_$pkgId") == label }
    }

    // ---- focus + diagnostic -----------------------------------------------------------------------------

    protected fun scrollTutorTo(tag: String) = scrollListTo("tutor_list", tag)
    protected fun scrollTutorToText(text: String) {
        waitFor("question \"${text.take(30)}\"") { runCatching { rule.onNodeWithTag("tutor_list").performScrollToNode(hasText(text)); true }.getOrDefault(false) }
        rule.waitForIdle()
    }
    protected fun tapTutor(tag: String) { scrollTutorTo(tag); rule.onNodeWithTag(tag).performClick(); rule.waitForIdle() }

    protected fun chooseFocus(vararg tags: String) {
        waitForTag("focus_continue")
        tags.forEach { tapTutor(it) }
        tapTutor("focus_continue")
    }

    /** Answers every diagnostic question the way [answer] says, by tapping the option. Ends on the starting-point screen. */
    protected fun runDiagnostic(pkgId: String, answer: (TutorQuestion) -> String) {
        val total = cache(pkgId).session.questions.size
        for (i in 0 until total) {
            waitForText(str(R.string.tutor_question_count, i + 1, total))
            val q = cache(pkgId).session.questions[i]
            scrollTutorToText(q.prompt)                       // below the fold at large text sizes: a student scrolls
            answerCurrent(q, answer(q))
            if (i < total - 1) waitForText(str(R.string.tutor_question_count, i + 2, total))
        }
        waitForText(str(R.string.tutor_assessment_title))
    }

    /** Taps the answer: that is the whole step, the next question (or the starting-point screen) follows by itself. */
    protected fun answerCurrent(q: TutorQuestion, option: String) {
        tapTutor(if (option == UNKNOWN_ANSWER) "answer_unknown" else "answer_choice_$option")
    }

    protected fun right(pkgId: String): (TutorQuestion) -> String = { q -> concept(pkgId, q.id).correctOptionId }
    protected fun wrong(pkgId: String): (TutorQuestion) -> String = { q -> q.options.first { it.id != concept(pkgId, q.id).correctOptionId }.id }

    /** From the starting-point screen into the scene. */
    protected fun startConversation() {
        tapTutor("start_tutor")
        waitFor("the explainer scene") { has("explainer_stage") && has("dialogue_text") && !has("typing_indicator") }
    }

    /** Welcome → Learn → focus → diagnostic → scene, for a guest. Returns the cache once the explanation has started. */
    protected fun reachScene(pkgId: String, focus: List<String> = emptyList(), answer: (TutorQuestion) -> String = { UNKNOWN_ANSWER }): TutorCache {
        launch(); chooseGuest(); openLearn()
        startExplanation(pkgId)
        chooseFocus(*focus.toTypedArray())
        runDiagnostic(pkgId, answer)
        startConversation()
        return cache(pkgId)
    }

    // ---- the explainer scene ----------------------------------------------------------------------------

    protected fun sceneReady() = waitFor("a settled dialogue page") { has("dialogue_text") && !has("typing_indicator") }

    /** The page of dialogue on screen now must sit fully inside the dialogue box (nothing clipped or pushed out). */
    protected fun assertDialogueFits(where: String) {
        val box = boundsOf("dialogue_box"); val text = boundsOf("dialogue_text")
        assertTrue("$where: dialogue text $text leaves the dialogue box $box", text.top >= box.top - 1f && text.bottom <= box.bottom + 1f && text.left >= box.left - 1f && text.right <= box.right + 1f)
        if (has("message_source")) {
            val chip = boundsOf("message_source")
            assertTrue("$where: source chip $chip leaves the box $box", chip.top >= box.top - 1f && chip.bottom <= box.bottom + 1f)
            assertTrue("$where: source chip $chip overlaps the text $text", chip.top >= text.bottom - 1f)
        }
    }

    /** Turns the pages of the message on screen until the choice bar appears; returns every page's text. [viaSemantics]: without any touch (as a screen reader does). */
    protected fun readPages(where: String, viaSemantics: Boolean = false, onPage: (Int, String) -> Unit = { _, _ -> }): List<String> {
        sceneReady()
        val pages = mutableListOf<String>()
        while (true) {
            val page = textOf("dialogue_text")
            assertDialogueFits("$where page ${pages.size + 1}")
            onPage(pages.size, page)
            pages += page
            if (has("tutor_rephrase")) return pages
            check(pages.size < 30) { "$where: still not on the last page after ${pages.size} pages" }
            // On a quick-check page the middle of the box is an answer button; the page turns with Next there.
            if (viaSemantics) rule.onNodeWithTag("dialogue_box").performSemanticsAction(SemanticsActions.OnClick)
            else if (has("quick_check")) rule.onNodeWithTag("dialogue_next").performClick() else rule.onNodeWithTag("dialogue_box").performClick()
            rule.waitForIdle()
            waitFor("$where: page ${pages.size + 1}") { textOf("dialogue_text") != page || has("tutor_rephrase") }
            if (has("tutor_rephrase") && textOf("dialogue_text") == page) return pages
        }
    }

    /** The page the student reads is what the message says, in order, with nothing lost (a scripted turn: its explanation; the title and lead-in sit above the box's text). */
    protected fun assertPagesCoverMessage(where: String, pages: List<String>, message: String) {
        val body = bodyOf(message)
        assertEquals("$where: the pages together are not the message", norm(body).replace(" ", ""), norm(pages.joinToString(" ")).replace(" ", ""))
    }

    /** The explanation part of a scripted turn ("concept / lead-in / explanation"); any other message is its own body. */
    protected fun bodyOf(message: String): String =
        TutorRules.splitScripted(message, listOf(attention, resnet, zhSpaced, retrieval).flatMap { p -> script(p).blueprint.concepts.map { it.question.concept } })?.body ?: message

    protected fun lastTutorMessage(pkgId: String) = cache(pkgId).session.messages.last { it.role != "user" }

    /**
     * Presses one of the choice buttons and waits for the explainer's new message. The wait looks at the screen only: the app
     * saves its session with an AtomicFile, and a test that reads that file while a write is in flight makes the app lose the write.
     */
    protected fun choose(pkgId: String, tag: String) {
        val before = textOf("dialogue_text")
        val count = cache(pkgId).session.messages.size
        rule.onNodeWithTag(tag).performClick()
        waitFor("a reply after $tag (button at ${runCatching { boundsOf(tag) }}; typing=${has("typing_indicator")} text=${has("dialogue_text")})") {
            has("dialogue_text") && !has("typing_indicator") && textOf("dialogue_text") != before
        }
        rule.waitForIdle()
        assertTrue("$tag added a message", cache(pkgId).session.messages.size > count)
    }

    protected fun askQuestion(text: String) {
        rule.onNodeWithTag("tutor_ask").performClick()
        waitForTag("tutor_input")
        rule.onNodeWithTag("tutor_input").performTextInput(text)
        rule.onNodeWithTag("tutor_send").performClick()
        waitFor("the question to be answered") { has("dialogue_text") && !has("typing_indicator") && !has("ask_sheet") }
    }

    protected fun conceptsInTeachingOrder(pkgId: String): List<TutorQuestion> = TutorRules.orderedQuestions(cache(pkgId).session)
    protected fun conceptLine(i: Int, n: Int) = str(R.string.explainer_concept, i, n)

    protected fun understanding(pkgId: String, q: TutorQuestion): Understanding = cache(pkgId).session.assessment.first { it.questionId == q.id }.evidence

    /** Latin words (3+ letters) in the visible strings that are not part of the course's own content: untranslated interface text. */
    protected fun latinLeaks(pkgId: String): List<String> {
        val s = script(pkgId).blueprint.concepts
        val content = (s.flatMap { c -> listOf(c.explanation, c.simpler, c.example, c.question.prompt, c.question.concept) + c.question.options.map { it.text } } +
            runBlocking { deps.packages.load(pkgId).getOrThrow().sources.map { it.text } } + title(pkgId) +
            runBlocking { deps.packages.list().map { it.title } }).joinToString(" ")   // the shelf lists every course by its own (maybe English) title
        return everythingShown().flatMap { t -> Regex("[A-Za-z]{3,}").findAll(t).map { it.value }.toList() }.filter { it != "PDF" && it !in content }.distinct()
    }

    /** Any Text that is cut short: an ellipsis, or fewer characters laid out than the string has (hasVisualOverflow is unreliable under Robolectric). */
    protected fun clippedTexts(): List<String> =
        rule.onAllNodes(SemanticsMatcher("text") { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }).fetchSemanticsNodes().mapNotNull { n ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.firstOrNull()
            if (r != null && ((0 until r.lineCount).any { r.isLineEllipsized(it) } || r.getLineEnd(r.lineCount - 1, visibleEnd = true) < r.layoutInput.text.length)) n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } else null
        }
}
