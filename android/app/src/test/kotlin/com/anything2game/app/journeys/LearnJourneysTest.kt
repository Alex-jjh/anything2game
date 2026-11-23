package com.anything2game.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.anything2game.app.R
import com.anything2game.app.platform.SimulatedContextSource
import com.anything2game.contract.FrameId
import com.anything2game.contract.tutor.Facet
import com.anything2game.app.ui.reading.DialoguePaging
import com.anything2game.contract.tutor.TutorIntent
import com.anything2game.contract.tutor.TutorRules
import com.anything2game.contract.tutor.UNKNOWN_ANSWER
import com.anything2game.contract.tutor.Understanding
import com.anything2game.engine.context.ContextSignals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * QA of the Learn tab as a first-time student meets it (docs/product/team-review-2026-11-20.md): shelf -> focus ->
 * diagnostic -> starting points -> explainer scene -> choices -> back to the shelf. Real MainActivity, real taps.
 */
class LearnJourneysTest : LearnJourneyBase() {
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    /** Gets the saved session of [pkgId] (guest) to the last concept without the UI, the way the view model would. */
    private fun seedUntilLastConcept(pkgId: String) = runBlocking {
        val repo = deps.tutors
        var c = repo.open(pkgId).cache
        c = repo.setFocus(c, emptyList(), "")
        c = c.copy(draftAnswers = c.session.questions.associate { it.id to UNKNOWN_ANSWER }, questionIndex = c.session.questions.lastIndex)
        c = c.copy(session = repo.assess(c, "a"))
        c = c.copy(session = repo.turn(c, TutorIntent.START, "", "s"))
        repeat(c.session.questions.size - 1) { i -> c = c.copy(session = repo.turn(c, TutorIntent.CONTINUE, "", "c$i")) }
        repo.save(c)
    }

    // ---- the shelf --------------------------------------------------------------------------------------

    @Test fun learn_lists_the_four_preset_courses_each_with_start_explanation() {
        launch(); chooseGuest()
        waitForTag("home_LEARN")                       // Learn is the default tab
        for (id in listOf(attention, resnet, zhSpaced, retrieval)) {
            scrollLearnTo("start_explanation_$id")
            assertEquals(str(R.string.tutor_start_explanation), textOf("start_explanation_$id"))
            rule.onNodeWithTag("start_explanation_$id").assertHasClickAction()
        }
    }

    @Test fun learn_lists_only_courses_with_an_explanation_and_has_no_dead_end_list_of_other_materials() {
        launch(); chooseGuest(); waitForTag("home_LEARN")
        waitFor("the library to load") { has("start_explanation_$attention") }
        // cs231n and mapreduce ship without a tutor script: no explanation card for them ...
        val others = listOf("demo_cs231n_l1", "demo_mapreduce")
        for (id in others) assertFalse("$id has an explanation card", exists(hasTestTag("start_explanation_$id")))
        // ... and no second list of materials either: every card of Learn is an explanation, and the other courses' titles are nowhere in it.
        for (id in listOf(attention, resnet, zhSpaced, retrieval)) scrollLearnTo("start_explanation_$id")
        for (id in others) assertFalse("$id is listed in Learn although it has no explanation", exists(hasText(title(id))))
        assertFalse("a 'View material' button leads to a screen that says there is no explanation", exists(hasText("View material")))
        // Those courses live in Practice.
        openPractice()
        for (id in others) assertTrue("${title(id)} is in Practice", run { scrollPracticeTo(title(id)); textShown(title(id)) })
    }

    // ---- journey 1 --------------------------------------------------------------------------------------

    @Test fun guest_attention_results_focus_end_to_end_and_back_on_the_shelf() {
        launch(); chooseGuest(); waitForTag("home_LEARN")
        startExplanation(attention)

        // The focus step comes first: four facets, free text, "let the explainer decide" is the default.
        waitForText(str(R.string.focus_title))
        listOf(R.string.focus_background, R.string.focus_method, R.string.focus_result, R.string.focus_limitation, R.string.focus_decide).forEach { assertTrue("chip ${str(it)}", textShown(str(it))) }
        assertFalse("a pre-written course has nothing to read a free-text note with", has("focus_note"))
        assertTrue("explainer decides by default", rule.onNodeWithTag("focus_decide").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
        chooseFocus("focus_result")
        assertEquals("the choice is remembered", listOf(Facet.RESULT), cache(attention).session.focus)

        // The diagnostic asks the results concept first; the focus is repeated on screen.
        waitForText(str(R.string.tutor_question_count, 1, 4))
        waitForText(str(R.string.focus_summary, str(R.string.focus_result)))
        val order = cache(attention).session.questions
        assertEquals(listOf("q3", "q1", "q2", "q4"), order.map { it.id })
        assertTrue("the first question is about results", textShown("Translation results"))

        // One right (results), one wrong (background), one "I do not know" (method), one wrong (limits).
        val given = mapOf("q3" to concept(attention, "q3").correctOptionId, "q1" to wrong(attention)(order[1]), "q2" to UNKNOWN_ANSWER, "q4" to wrong(attention)(order[3]))
        runDiagnostic(attention) { given.getValue(it.id) }

        // Starting points: each concept with the verdict its answer deserves, in the order the student will meet them.
        val badge = mapOf(Understanding.DEMONSTRATED to R.string.tutor_correct, Understanding.NEEDS_CLARIFICATION to R.string.tutor_clarify, Understanding.NOT_YET_ASSESSED to R.string.tutor_not_assessed)
        val expected = mapOf("q3" to Understanding.DEMONSTRATED, "q1" to Understanding.NEEDS_CLARIFICATION, "q2" to Understanding.NOT_YET_ASSESSED, "q4" to Understanding.NEEDS_CLARIFICATION)
        order.forEach { q ->
            assertEquals("saved verdict of ${q.id}", expected.getValue(q.id), understanding(attention, q))
            rule.onNodeWithTag("tutor_list").performScrollToNode(hasText(q.concept)); rule.waitForIdle()
            // The last card can sit on the very edge of the screen with its verdict still below the fold: a student drags it up.
            if (rule.onNode(hasText(q.concept)).fetchSemanticsNode().boundsInRoot.bottom > rootHeight() - 300f) {
                rule.onNodeWithTag("tutor_list").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(centerX, centerY + 200f), androidx.compose.ui.geometry.Offset(centerX, centerY - 200f), 150) }
                rule.waitForIdle()
            }
            val c = rule.onNode(hasText(q.concept)).fetchSemanticsNode().boundsInRoot
            val label = str(badge.getValue(expected.getValue(q.id)))
            val below = rule.onAllNodes(hasText(label)).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.top >= c.top && it.top - c.bottom < 240f }
            assertTrue("\"${q.concept}\" at $c should carry \"$label\"; nodes with that label: ${rule.onAllNodes(hasText(label)).fetchSemanticsNodes().map { it.boundsInRoot }}", below.isNotEmpty())
        }
        startConversation()

        // The explainer begins with what is still open: the concept the student just proved they know (Results, chosen) comes after it.
        val teaching = conceptsInTeachingOrder(attention)
        assertEquals("q1", teaching.first().id)
        assertEquals(str(R.string.explainer_name), textOf("dialogue_speaker"))
        assertTrue(has("player_avatar"))
        assertEquals(listOf("q1", "q2", "q4", "q3"), teaching.map { it.id })

        teaching.forEachIndexed { i, q ->
            assertEquals(conceptLine(i + 1, 4), textOf("concept_count"))
            val c = concept(attention, q.id)
            val pages = readPages("concept ${q.concept}")
            val msg = lastTutorMessage(attention)
            assertPagesCoverMessage(q.concept, pages, msg.text)
            assertTrue("starts with the concept name", msg.text.startsWith(q.concept))
            // The concept name is the box's title and the lead-in a muted line under it; the first page is the real explanation.
            val first = pages.first()
            assertEquals(q.concept, textOf("dialogue_title").takeIf { has("dialogue_title") })
            assertFalse("the first page repeats the concept name or the lead-in: $first", first.startsWith(q.concept) || first.contains("You weren't sure") || first.contains("Your answer mixed") || first.contains("You got this one right"))
            // A course written as a scene says its lines; one without lines says the explanation paragraph.
            val said = if (c.beats.isEmpty()) c.explanation else c.beats.joinToString("\n") { it.text }
            assertTrue("body is the script's explanation", norm(msg.text).contains(norm(said)))
            val prefix = when (understanding(attention, q)) { Understanding.DEMONSTRATED -> "You got this one right"; Understanding.NEEDS_CLARIFICATION -> "Your answer mixed something up"; else -> "You weren't sure" }
            assertTrue("prefix for ${q.id}: $prefix", msg.text.contains(prefix))

            // Show source -> the page of the very passage the concept rests on.
            // The chip shows the passage of the line on screen (a scene line's own, else the concept's).
            val refs = c.beats.lastOrNull()?.sourceRefs?.takeIf { it.isNotEmpty() } ?: q.sourceRefs
            val page = pageLabelOf(attention, refs.first().chunkId)
            assertEquals(str(R.string.explainer_show_source, page), textOf("message_source"))
            rule.onNodeWithTag("message_source").performClick()
            waitForText(str(R.string.tutor_source_title))
            assertTrue("page $page shown", textShown(str(R.string.evidence_page, page)))
            assertTrue("the quote is on the page, marked", norm(textOf("source_highlighted")).contains(norm(refs.first().quote)))
            tapText(str(R.string.tutor_close))
            waitFor("source dialog closed") { !textShown(str(R.string.tutor_source_title)) }

            if (i < teaching.lastIndex) {
                choose(attention, "tutor_rephrase")
                readPages("rephrase ${q.id}"); assertTrue("rephrase says the simpler version", norm(lastTutorMessage(attention).text).contains(norm(c.simpler)))
                choose(attention, "tutor_example")
                readPages("example ${q.id}"); assertTrue("example says the example", norm(lastTutorMessage(attention).text).contains(norm(c.example)))
                choose(attention, "tutor_continue")
                waitFor("next concept line") { textOf("concept_count") == conceptLine(i + 2, 4) }
            } else {
                // The last concept ends the walk: no Continue, a way back to the shelf, and the closing line.
                assertFalse(has("tutor_continue"))
                assertEquals(str(R.string.explainer_finish), textOf("tutor_finish"))
                assertTrue(textShown(str(R.string.tutor_all_introduced)))
            }
        }
        tap(rule.onNodeWithTag("tutor_finish"))
        waitForTag("wrap_up"); tap(rule.onNodeWithTag("wrapup_shelf"))
        waitForTag("home_LEARN")
        assertLearnCard(attention, str(R.string.tutor_card_explained))     // the walk reached its last concept: the card says so
        // The saved explanation is still there: opening the card again lands on the last concept, not a restart.
        startExplanation(attention)
        waitFor("the scene again") { has("dialogue_text") }
        assertEquals(conceptLine(4, 4), textOf("concept_count"))
    }

    @Test fun when_the_focus_concept_was_answered_wrong_the_explainer_starts_there_with_a_clarification() {
        reachScene(attention, listOf("focus_result"), wrong(attention))
        val first = conceptsInTeachingOrder(attention).first()
        assertEquals("q3", first.id)
        assertEquals(Understanding.NEEDS_CLARIFICATION, understanding(attention, first))
        assertTrue(lastTutorMessage(attention).text.contains("Your answer mixed something up"))
    }

    @Test fun letting_the_explainer_decide_keeps_the_scripts_own_order_and_starts_with_what_is_not_known() {
        // q1 right, the rest unknown: the explainer must not begin with the concept the student already knows.
        val q1 = concept(attention, "q1").correctOptionId
        reachScene(attention, emptyList()) { if (it.id == "q1") q1 else UNKNOWN_ANSWER }
        val teaching = conceptsInTeachingOrder(attention)
        assertNotEquals("q1", teaching.first().id)
        assertEquals("q1", teaching.last().id)
        assertTrue(textOf("dialogue_text").isNotBlank())
    }

    @Test fun back_to_shelf_on_the_last_concept_leaves_learn_showing_the_course_as_continuable() {
        seedUntilLastConcept(attention)
        launch(); chooseGuest(); waitForTag("home_LEARN")
        startExplanation(attention)
        waitFor("last concept") { has("dialogue_text") }
        readPages("last concept")
        tap(rule.onNodeWithTag("tutor_finish"))
        waitForTag("wrap_up"); tap(rule.onNodeWithTag("wrapup_shelf"))
        waitForTag("home_LEARN")
        assertLearnCard(attention, str(R.string.tutor_card_explained))
    }

    @Test fun focus_accepts_several_facets_orders_the_questions_by_them_and_offers_no_free_text_for_a_scripted_course() {
        launch(); chooseGuest(); waitForTag("home_LEARN")
        startExplanation(attention)
        waitForTag("focus_continue")
        tapTutor("focus_limitation"); tapTutor("focus_method")
        assertFalse("picking facets leaves 'let the explainer decide' off", rule.onNodeWithTag("focus_decide").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
        assertFalse("no free-text field where nothing can read it", has("focus_note") || textShown(str(R.string.focus_other)))
        tapTutor("focus_continue")
        waitForText(str(R.string.tutor_question_count, 1, 4))
        val c = cache(attention).session
        assertEquals(listOf(Facet.METHOD, Facet.LIMITATION), c.focus)
        assertEquals("the chosen facets first, nothing dropped, the rest in the script's order", listOf("q2", "q4", "q1", "q3"), c.questions.map { it.id })
        assertEquals("", c.focusNote)
        waitForText(str(R.string.focus_summary, listOf(R.string.focus_method, R.string.focus_limitation).joinToString(" · ") { str(it) }))
        assertFalse(has("focus_summary_note"))
        waitForText("Multi-head attention")
    }

    @Test fun a_first_time_student_sees_an_explanation_to_start_on_the_first_screen_of_learn() {
        launch(); chooseGuest(); waitForTag("home_LEARN")
        waitFor("the library to load") { has("start_explanation_$attention") }
        val nav = boundsOf("home_navigation").top
        val b = rule.onNodeWithTag("start_explanation_$attention").fetchSemanticsNode().boundsInRoot
        assertTrue("the first course's Start button at $b is below the bottom bar (top $nav): the student has to scroll past the upload card", b.bottom <= nav && b.top >= 0f)
    }

    @Test fun no_concept_opens_with_a_page_that_is_only_a_heading_and_a_lead_in() {
        // The title and lead-in sit above the text; the pages are the explanation, and the first one is real content.
        val bad = mutableListOf<String>()
        for ((pkg, lang) in listOf(attention to "en", resnet to "en", zhSpaced to "zh")) for (c in script(pkg).blueprint.concepts) {
            val message = "${c.question.concept}\n${TutorRules.leadIn(Understanding.NOT_YET_ASSESSED, lang == "zh")}\n${c.explanation}"
            val parts = TutorRules.splitScripted(message, listOf(c.question.concept))
            if (parts == null) { bad += "$pkg ${c.question.id}: not split"; continue }
            val first = DialoguePaging.pages(parts, DialoguePaging.LATIN_BUDGET, DialoguePaging.CJK_BUDGET).first()
            // Real content: at least a whole sentence of the explanation, never a heading or a line that ends in a colon.
            if (first.trimEnd().let { it.endsWith(":") || it.endsWith("：") } || first.length < 20 || first.startsWith(c.question.concept) || !c.explanation.startsWith(first.take(20))) bad += "$pkg ${c.question.id} [${first.length}] ${first.replace("\n", " / ").take(70)}"
        }
        assertEquals("pages that open without real content", emptyList<String>(), bad)
    }

    @Test fun the_dialogue_box_shows_the_concept_as_its_title_and_the_lead_in_only_on_the_first_page() {
        reachScene(attention, listOf("focus_result"), right(attention))
        val q = conceptsInTeachingOrder(attention).first()
        sceneReady()
        assertEquals(q.concept, textOf("dialogue_title"))
        assertEquals(TutorRules.leadIn(understanding(attention, q), false), textOf("dialogue_lead_in"))
        assertEquals(str(R.string.explainer_name), textOf("dialogue_speaker"))
        assertTrue("the first page is explanation", norm(lastTutorMessage(attention).text).contains(norm(textOf("dialogue_text"))))
        if (!has("tutor_rephrase")) {
            rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle()
            assertEquals("the title stays", q.concept, textOf("dialogue_title"))
            assertFalse("the lead-in is said once", has("dialogue_lead_in"))
        }
        // A typed question has no such structure: the player's line and the reply show as they are.
        readPages("c1")
        askQuestion("Does this hold for every task?")
        assertFalse(has("dialogue_title") || has("dialogue_lead_in"))
        rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle()
        assertFalse(has("dialogue_title") || has("dialogue_lead_in"))
    }

    @Test fun poking_the_players_sidekick_does_not_turn_the_page_but_tapping_the_stage_does() {
        reachScene(attention, listOf("focus_result"), right(attention))
        sceneReady()
        assertTrue("the first message has more than one page", has("dialogue_next"))
        val first = textOf("dialogue_text")
        rule.onNodeWithTag("explainer_stage").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.92f, height * 0.04f)) }   // the bare backdrop: Aya herself is pokeable too
        rule.waitForIdle()
        val page = textOf("dialogue_text")
        assertNotEquals("a tap on the stage backdrop turns the page", first, page)
        if (has("dialogue_next")) {
            rule.onNodeWithTag("player_avatar").performClick(); rule.waitForIdle()
            rule.onNodeWithTag("player_avatar").performClick(); rule.waitForIdle()
            assertEquals("a poke is not a page turn", page, textOf("dialogue_text"))
        }
        assertEquals(conceptLine(1, 4), textOf("concept_count"))
    }

    // ---- journey 2 --------------------------------------------------------------------------------------

    @Test fun leaving_mid_explanation_and_coming_back_restores_the_concept_and_the_message_rotation_keeps_the_page() {
        reachScene(attention, listOf("focus_result"), right(attention))
        readPages("concept 1"); choose(attention, "tutor_continue")
        waitFor("concept 2") { textOf("concept_count") == conceptLine(2, 4) }
        val message = lastTutorMessage(attention)
        sceneReady()
        // Page 2 of the message.
        val first = textOf("dialogue_text")
        rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle()
        val second = textOf("dialogue_text")
        assertNotEquals("the message has several pages", first, second)

        RuntimeEnvironment.setQualifiers("+land"); rule.waitForIdle()
        waitFor("scene in landscape") { has("dialogue_text") }
        assertEquals("rotation keeps the page", second, textOf("dialogue_text"))
        assertEquals(conceptLine(2, 4), textOf("concept_count"))
        RuntimeEnvironment.setQualifiers("+port"); rule.waitForIdle()
        waitFor("scene in portrait") { has("dialogue_text") }
        assertEquals(second, textOf("dialogue_text"))

        systemBack(); waitForTag("home_LEARN")
        assertLearnCard(attention, str(R.string.tutor_card_continue_concept, 2, 4))     // the card says where the student is
        startExplanation(attention)
        waitFor("scene after coming back") { has("dialogue_text") && !has("typing_indicator") }
        assertEquals("same concept", conceptLine(2, 4), textOf("concept_count"))
        assertEquals("same message", message, lastTutorMessage(attention))
        assertEquals("the message is read from its first page again", first, textOf("dialogue_text"))

        restartProcess()
        waitForTag("home_LEARN")
        startExplanation(attention)
        waitFor("scene after a process restart") { has("dialogue_text") && !has("typing_indicator") }
        assertEquals("same concept after restart", conceptLine(2, 4), textOf("concept_count"))
        assertEquals("same message after restart", message, lastTutorMessage(attention))
        assertEquals(first, textOf("dialogue_text"))
        // ... and the walk can be finished from here.
        readPages("after restart")
        assertTrue(has("tutor_continue"))
    }

    @Test fun choosing_an_answer_moves_on_by_itself_and_previous_goes_back_to_change_it() {
        launch(); chooseGuest(); waitForTag("home_LEARN")
        startExplanation(attention); chooseFocus()
        val qs = cache(attention).session.questions
        waitForText(str(R.string.tutor_question_count, 1, 4))
        assertFalse("nothing to press before an answer is chosen", has("diagnostic_next") || has("diagnostic_previous"))
        answerCurrent(qs[0], concept(attention, qs[0].id).correctOptionId)         // one tap, no "Next"
        waitForText(str(R.string.tutor_question_count, 2, 4))
        assertFalse("the new question is unanswered: no Next yet", has("diagnostic_next"))
        val second = qs[1].options.first().id
        answerCurrent(qs[1], second)
        waitForText(str(R.string.tutor_question_count, 3, 4))
        assertEquals(setOf(qs[0].id, qs[1].id), cache(attention).draftAnswers.keys)
        // Previous goes back; the earlier choice is still marked and can be changed (which moves on again).
        tapTutor("diagnostic_previous")
        waitForText(str(R.string.tutor_question_count, 2, 4))
        waitFor("earlier answer selected") { rule.onNodeWithTag("answer_choice_$second").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true }
        scrollTutorTo("diagnostic_next")                                       // an answered question can be left with Next
        assertTrue(has("diagnostic_next"))
        val other = qs[1].options.first { it.id != second }.id
        answerCurrent(qs[1], other)
        waitForText(str(R.string.tutor_question_count, 3, 4))
        assertEquals(other, cache(attention).draftAnswers[qs[1].id])
        // Leaving here keeps the place; the card says "Continue", not "Start".
        systemBack(); waitForTag("home_LEARN")
        assertLearnCard(attention, str(R.string.tutor_card_continue))
        startExplanation(attention)
        waitForText(str(R.string.tutor_question_count, 3, 4))
        assertFalse("the focus question is asked again after it was answered", has("focus_continue"))
        restartProcess(); waitForTag("home_LEARN"); startExplanation(attention)
        waitForText(str(R.string.tutor_question_count, 3, 4))
        // The last answer opens the starting-point screen with no further tap.
        answerCurrent(qs[2], UNKNOWN_ANSWER); waitForText(str(R.string.tutor_question_count, 4, 4))
        answerCurrent(qs[3], UNKNOWN_ANSWER); waitForText(str(R.string.tutor_assessment_title))
        scrollTutorTo("start_tutor"); assertTrue(has("start_tutor"))
        assertEquals(4, cache(attention).session.assessment.size)
    }

    // ---- journey 3 --------------------------------------------------------------------------------------

    private fun noLeaks(pkgId: String, where: String) {
        rule.waitForIdle()
        assertEquals("English leaked into the Chinese UI at $where", emptyList<String>(), latinLeaks(pkgId))
        assertEquals("clipped text at $where", emptyList<String>(), clippedTexts())
    }

    @Test fun chinese_course_end_to_end_in_a_chinese_phone_without_english_or_clipped_text() {
        RuntimeEnvironment.setQualifiers("+zh")
        launch(); waitForTag("welcome"); chooseGuest(); waitForTag("home_LEARN")
        scrollLearnTo("start_explanation_$zhSpaced")
        assertEquals("开始讲解", textOf("start_explanation_$zhSpaced"))
        noLeaks(zhSpaced, "Learn shelf")
        startExplanation(zhSpaced)
        waitForText(str(R.string.focus_title)); noLeaks(zhSpaced, "focus step")
        chooseFocus("focus_method")
        waitForText(str(R.string.tutor_question_count, 1, 3)); noLeaks(zhSpaced, "diagnostic")
        // method concept first; right, wrong, unknown
        val qs = cache(zhSpaced).session.questions
        assertEquals("q3", qs.first().id)
        val plan = mapOf("q3" to concept(zhSpaced, "q3").correctOptionId, "q1" to wrong(zhSpaced)(qs[1]), "q2" to UNKNOWN_ANSWER)
        waitForText(qs[0].prompt)
        answerCurrent(qs[0], plan.getValue(qs[0].id)); waitForText(str(R.string.tutor_question_count, 2, 3)); noLeaks(zhSpaced, "diagnostic 2")
        answerCurrent(qs[1], plan.getValue(qs[1].id)); waitForText(str(R.string.tutor_question_count, 3, 3))
        answerCurrent(qs[2], plan.getValue(qs[2].id)); waitForText(str(R.string.tutor_assessment_title)); noLeaks(zhSpaced, "starting points")
        assertTrue(textShown(str(R.string.tutor_correct)) && textShown(str(R.string.tutor_clarify)) && textShown(str(R.string.tutor_not_assessed)))
        startConversation()
        assertEquals("阿雅", textOf("dialogue_speaker"))
        val teaching = conceptsInTeachingOrder(zhSpaced)
        teaching.forEachIndexed { i, q ->
            val pages = readPages("zh ${q.concept}") { n, _ -> noLeaks(zhSpaced, "page ${n + 1} of ${q.concept}") }
            assertPagesCoverMessage(q.concept, pages, lastTutorMessage(zhSpaced).text)
            assertTrue(textOf("message_source").let { it.contains("查看原文") && it.contains("第") })
            noLeaks(zhSpaced, "choice bar of ${q.concept}")
            if (i < teaching.lastIndex) { choose(zhSpaced, "tutor_rephrase"); readPages("zh rephrase"); choose(zhSpaced, "tutor_example"); readPages("zh example"); choose(zhSpaced, "tutor_continue") }
        }
        assertEquals("讲完了 · 看总结", textOf("tutor_finish"))
        // Source, Ask, Log in Chinese too.
        rule.onNodeWithTag("message_source").performClick(); waitForText(str(R.string.tutor_source_title)); noLeaks(zhSpaced, "source dialog"); tapText(str(R.string.tutor_close))
        waitFor("closed") { !textShown(str(R.string.tutor_source_title)) }
        rule.onNodeWithTag("tutor_ask").performClick(); waitForTag("tutor_input"); noLeaks(zhSpaced, "ask sheet")
        tapText(str(R.string.explainer_cancel)); waitFor("ask sheet closed") { !has("ask_sheet") }
        rule.onNodeWithTag("explainer_log").performClick(); waitForTag("explainer_log_list"); noLeaks(zhSpaced, "log")
        tap(rule.onNodeWithTag("tutor_finish")); waitForTag("wrap_up"); tap(rule.onNodeWithTag("wrapup_shelf")); waitForTag("home_LEARN")
    }

    // ---- journey 4 --------------------------------------------------------------------------------------

    @Test fun learn_sessions_belong_to_the_profile_the_other_profile_never_sees_them() {
        reachScene(attention, listOf("focus_result"), right(attention))
        readPages("guest concept 1"); choose(attention, "tutor_continue")
        waitFor("guest on concept 2") { textOf("concept_count") == conceptLine(2, 4) }
        systemBack(); waitForTag("home_LEARN")

        switchProfile(); chooseLin(); openLearn()
        startExplanation(attention)
        waitForText(str(R.string.focus_title))                           // Lin starts fresh
        assertFalse("the guest's explanation leaks into Lin", has("dialogue_text"))
        assertTrue("Lin has no session of the guest's", cache(attention).session.messages.isEmpty() && cache(attention).session.focus.isEmpty())
        chooseFocus("focus_method")
        waitForText(str(R.string.tutor_question_count, 1, 4))
        val lin = cache(attention).session.questions
        answerCurrent(lin[0], UNKNOWN_ANSWER)
        waitForText(str(R.string.tutor_question_count, 2, 4))
        systemBack(); waitForTag("home_LEARN")

        switchProfile(); chooseGuest(); openLearn()
        startExplanation(attention)
        waitFor("guest's explanation") { has("dialogue_text") }
        assertEquals("the guest's own place", conceptLine(2, 4), textOf("concept_count"))
        assertEquals(listOf(Facet.RESULT), cache(attention).session.focus)
        systemBack(); waitForTag("home_LEARN")

        switchProfile(); chooseLin(); openLearn()
        startExplanation(attention)
        waitForText(str(R.string.tutor_question_count, 2, 4))            // Lin's own place, not the guest's scene
        assertEquals(listOf(Facet.METHOD), cache(attention).session.focus)
        restartProcess(); waitForTag("home_LEARN")                       // Lin is remembered across a restart
        startExplanation(attention)
        waitForText(str(R.string.tutor_question_count, 2, 4))
    }

    // ---- journey 5 --------------------------------------------------------------------------------------

    @Test fun asking_offline_gets_an_honest_answer_the_conversation_continues_and_the_log_has_the_question() {
        reachScene(attention, emptyList())
        readPages("concept 1")
        val note = "Does this hold for every task?"
        rule.onNodeWithTag("tutor_ask").performClick(); waitForTag("tutor_input")
        rule.onNodeWithTag("tutor_send").assertIsNotEnabled()            // nothing to send yet
        askNow(note)
        // The question is the player's own line first ...
        assertEquals(note, textOf("dialogue_text"))
        assertNotEquals(str(R.string.explainer_name), textOf("dialogue_speaker"))
        rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle()
        // ... then the explainer says plainly what it cannot do.
        assertEquals(str(R.string.explainer_name), textOf("dialogue_speaker"))
        val answer = textOf("dialogue_text")
        assertTrue("answers honestly that it cannot answer free text: $answer", Regex("(?i)can't answer|cannot answer").containsMatchIn(answer))
        assertTrue("and says the question is kept: $answer", answer.contains("saved in the log"))
        readPages("after the question")
        // The conversation goes on: the same concept can be rephrased and the walk continues.
        choose(attention, "tutor_rephrase"); readPages("rephrase after question")
        choose(attention, "tutor_continue")
        waitFor("concept 2") { textOf("concept_count") == conceptLine(2, 4) }
        // The Log keeps the question and the answer.
        rule.onNodeWithTag("explainer_log").performClick(); waitForTag("explainer_log_list")
        rule.onNodeWithTag("explainer_log_list").performScrollToNode(hasText(note)); assertTrue(textShown(note))
        assertEquals(1, cache(attention).session.messages.count { it.text == note })
    }

    private fun askNow(text: String) {
        rule.onNodeWithTag("tutor_input").performTextInput(text)
        rule.onNodeWithTag("tutor_send").assertIsEnabled().performClick()
        waitFor("question answered") { has("dialogue_text") && !has("typing_indicator") && !has("ask_sheet") }
    }

    @Test fun the_log_of_an_english_course_is_in_english() {
        reachScene(attention, emptyList())
        readPages("c1"); choose(attention, "tutor_rephrase"); readPages("c1b"); choose(attention, "tutor_example")
        rule.onNodeWithTag("explainer_log").performClick(); waitForTag("explainer_log_list")
        val chinese = everythingShown().filter { cjk.containsMatchIn(it) }
        assertEquals("BUG: the student's own turns are logged in Chinese in an English course", emptyList<String>(), chinese)
    }

    @Test fun the_explainers_honest_answer_is_plain_not_developer_talk() {
        reachScene(attention, emptyList())
        readPages("c1")
        rule.onNodeWithTag("tutor_ask").performClick(); waitForTag("tutor_input")
        val note = textShownContaining("mock")
        assertFalse("the Ask sheet speaks of \"mock\" to a student", note)
    }

    @Test fun the_explainers_reply_to_a_question_has_no_developer_words() {
        reachScene(attention, emptyList()); readPages("c1")
        askQuestion("Does this hold for every task?")
        rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle()
        val reply = textOf("dialogue_text")
        assertFalse("reply to a student: $reply", Regex("(?i)mock|server model|configured").containsMatchIn(reply))
    }

    private fun textShownContaining(s: String) = everythingShown().any { it.contains(s, ignoreCase = true) }

    // ---- journey 6 --------------------------------------------------------------------------------------

    @Test fun everything_in_the_scene_works_without_the_tap_anywhere_gesture() {
        reachScene(attention, listOf("focus_result"), right(attention))
        // Every control a student must press is a real button with a name and a click action.
        sceneReady()
        if (!has("tutor_rephrase")) rule.onNodeWithTag("dialogue_next").assertHasClickAction()   // a page before the last has a Next page button
        val pages = readPages("c1", viaSemantics = true)
        assertTrue("the box can be advanced by a screen reader (it is a click action with a label)",
            rule.onNodeWithTag("dialogue_box").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) != null)
        listOf("tutor_rephrase", "tutor_example", "tutor_ask", "tutor_continue", "message_source", "explainer_log").forEach { tag ->
            val node = rule.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode()
            assertTrue("$tag has a click action", node.config.getOrNull(SemanticsActions.OnClick) != null)
        }
        rule.onNodeWithTag("explainer_stage").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).let { assertNotNull("the stage is described", it) }
        // Touch targets: nothing a finger must hit is smaller than 48dp.
        val density = app.resources.displayMetrics.density
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { n ->
            val b = n.touchBoundsInRoot
            val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.TestTag) ?: "?"
            assertTrue("\"$label\" is ${b.width / density} x ${b.height / density} dp (< 48)", b.height / density >= 47.5f && b.width / density >= 47.5f)
        }
        // The whole walk, no touch gesture on the stage at all: semantics clicks and buttons only.
        choose(attention, "tutor_continue")
        readPages("c2", viaSemantics = true)
        assertTrue(has("tutor_rephrase"))
    }

    @Test fun at_200_percent_text_size_the_dialogue_is_never_clipped_in_any_page_of_any_course() {
        RuntimeEnvironment.setFontScale(2f)
        reachScene(attention, listOf("focus_result"), right(attention))
        val teaching = conceptsInTeachingOrder(attention)
        teaching.forEachIndexed { i, q ->
            readPages("200% ${q.concept}")
            listOf("tutor_rephrase", "tutor_example", "tutor_ask", if (i < teaching.lastIndex) "tutor_continue" else "tutor_finish").forEach { tag ->
                assertTrue("$tag at ${boundsOf(tag)} is not fully on a ${rootHeight()}px screen at 200%", boundsOf(tag).bottom <= rootHeight() + 1f && boundsOf(tag).top >= 0f)
            }
            if (i < teaching.lastIndex) {
                choose(attention, "tutor_example"); readPages("200% example ${q.id}")
                choose(attention, "tutor_rephrase"); readPages("200% rephrase ${q.id}")
                choose(attention, "tutor_continue")
            }
        }
        assertEquals(emptyList<String>(), clippedTexts())
    }

    private fun rootHeight(): Float = rule.onRoot().fetchSemanticsNode().boundsInRoot.height

    @Test fun at_200_percent_the_chinese_course_dialogue_is_never_clipped() {
        RuntimeEnvironment.setQualifiers("+zh"); RuntimeEnvironment.setFontScale(2f)
        reachScene(zhSpaced, listOf("focus_result"), right(zhSpaced))
        conceptsInTeachingOrder(zhSpaced).forEachIndexed { i, q ->
            readPages("zh 200% ${q.concept}")
            if (i < 2) { choose(zhSpaced, "tutor_example"); readPages("zh 200% example"); choose(zhSpaced, "tutor_continue") }
        }
    }

    @Test fun at_200_percent_the_focus_and_diagnostic_steps_can_be_scrolled_to_every_button() {
        RuntimeEnvironment.setFontScale(2f)
        launch(); chooseGuest(); waitForTag("home_LEARN")
        startExplanation(attention)
        chooseFocus("focus_method", "focus_result")
        runDiagnostic(attention, right(attention))
        startConversation()
    }

    @Test fun at_200_percent_the_first_diagnostic_question_is_on_screen_without_scrolling() {
        RuntimeEnvironment.setFontScale(2f)
        launch(); chooseGuest(); waitForTag("home_LEARN")
        startExplanation(attention); chooseFocus()
        waitForText(str(R.string.tutor_question_count, 1, 4))
        val q = cache(attention).session.questions.first()
        val shown = rule.onAllNodes(hasText(q.prompt)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        val unknown = rule.onAllNodes(hasTestTag("answer_unknown")).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        assertTrue("BUG: at 200% the first question (${shown}) and its answers (${unknown}) start below the fold of a ${rootHeight()}px screen: the student sees only a header and must guess to scroll",
            shown != null && shown.bottom <= rootHeight() && unknown != null && unknown.bottom <= rootHeight())
    }

    // ---- journey 7 --------------------------------------------------------------------------------------

    @Test fun pause_when_im_walking_is_off_by_default_on_pauses_a_level_and_off_again_does_not() {
        val attentionTitle = "Attention Is All You Need (Transformer)"
        val level1 = "Why the Transformer Replaces Recurrence"
        launch(); chooseGuest(); openMe()
        scrollListTo("home_PROFILE", "setting_pause_walking")
        rule.onNodeWithTag("setting_pause_walking").assertIsOff()
        assertFalse(deps.homePreferences.pauseWhenWalking)

        // Off: walking only goes one-handed.
        openPractice(); startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CLASSIC)
        level.play(steps = 1)
        val sensors = deps.contextSource as SimulatedContextSource
        sensors.setActivity(ContextSignals.Activity.WALKING)
        waitForText(str(R.string.one_handed_badge))
        assertFalse(textShown(str(R.string.paused_title)))
        sensors.setActivity(ContextSignals.Activity.STILL)
        systemBack(); waitForTag("home_PRACTICE")

        // On: walking pauses.
        openMe(); scrollListTo("home_PROFILE", "setting_pause_walking")
        tap(rule.onNodeWithTag("setting_pause_walking"))
        rule.onNodeWithTag("setting_pause_walking").assertIsOn()
        assertTrue(deps.homePreferences.pauseWhenWalking)
        openPractice(); startLevel(attentionTitle, level1)
        val at = level.here()
        sensors.setActivity(ContextSignals.Activity.WALKING)
        waitForText(str(R.string.paused_title))
        assertEquals("pausing never moves the step", at, level.here())
        tapText(str(R.string.paused_continue))
        waitFor("pause gone") { !textShown(str(R.string.paused_title)) }
        sensors.setActivity(ContextSignals.Activity.STILL)
        systemBack(); waitForTag("home_PRACTICE")

        // Off again: walking does not pause any more.
        openMe(); scrollListTo("home_PROFILE", "setting_pause_walking")
        tap(rule.onNodeWithTag("setting_pause_walking"))
        rule.onNodeWithTag("setting_pause_walking").assertIsOff()
        assertFalse(deps.homePreferences.pauseWhenWalking)
        openPractice(); startLevel(attentionTitle, level1)
        sensors.setActivity(ContextSignals.Activity.WALKING)
        waitForText(str(R.string.one_handed_badge))
        assertFalse("paused although the setting is off again", textShown(str(R.string.paused_title)))
        sensors.setActivity(ContextSignals.Activity.STILL)

        // The setting survives a restart.
        systemBack(); waitForTag("home_PRACTICE")
        restartProcess(); waitForTag("tab_PROFILE"); openMe(); scrollListTo("home_PROFILE", "setting_pause_walking")
        rule.onNodeWithTag("setting_pause_walking").assertIsOff()
    }
}
