package com.anything2game.app.journeys

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.anything2game.app.ui.theme.CaseFileSkin
import com.anything2game.contract.game.*
import com.anything2game.contract.ContractJson
import kotlinx.serialization.json.*
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.test.core.app.ActivityScenario
import com.anything2game.app.R
import com.anything2game.app.MainActivity
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.contract.progress.ActivityOutcome
import com.anything2game.contract.space.*
import com.anything2game.contract.tutor.*
import com.anything2game.app.awaitIo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReviewedSpaceJourneysTest : LearnJourneyBase() {
    /** Steps before the requested one are done, as they would be for a student who got that far: a locked step cannot be tapped. */
    private var credited = 0

    private fun selectLaterConcept() {
        val (space, node) = awaitIo {
            val mat = deps.materials!!.materials().single { it.localPack?.packageId == attention }
            val parent = deps.spaces!!.list().first { mat.member in it.members }
            val target = mat.nodes.filter { it.activity is ActivityRef.Tutor }.last()
            mat.nodes.takeWhile { it.nodeId != target.nodeId }.forEach { before ->
                deps.learningProgress!!.record(before.activity, if (before.activity is ActivityRef.Tutor) ActivityOutcome.READ else ActivityOutcome.INDEPENDENT, 1_000_000L)
            }
            credited = deps.learningProgress!!.projection().completed.size
            parent to target
        }
        waitFor("parent Space card") { runCatching { rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag("open_space_${space.spaceId}")); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("open_space_${space.spaceId}"))
        waitForTag("show_route")
        val tag = "open_node_${node.nodeId}"
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_${node.nodeId}"))
        tap(rule.onNodeWithTag("route_node_${node.nodeId}"))
        waitForTag(tag)
        waitFor("node enabled") { runCatching { rule.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag(tag))
    }

    @Test fun start_on_a_later_route_concept_opens_it_directly_and_survives_recreation() {
        launch(); chooseGuest(); waitForTag("space_create")
        selectLaterConcept()
        waitFor("the requested concept", 5000) { has("explainer_stage") }
        // No starting assessment, no focus question, no diagnostic, and no stand-alone screen in front of the lesson.
        assertFalse(has("tutor_list")); assertFalse(has("focus_continue")); assertFalse(has("start_tutor"))
        sceneReady()
        val session = cache(attention).session
        val last = session.questions.lastIndex
        assertEquals(last, session.conceptPosition)
        assertEquals(session.questions.last().conceptId, TutorRules.orderedQuestions(session)[session.conceptPosition].conceptId)
        assertTrue("nobody was asked, so nothing is claimed about what they know", session.assessment.all { it.evidence == Understanding.NOT_ASKED })
        assertEquals(last + 1, session.conceptsIntroduced)
        // Only the requested concept was read: the lessons the route credited before it are not read or credited again.
        assertEquals(credited + 1, awaitIo { deps.learningProgress!!.projection().completed.size })
        val messages = session.messages.size
        scenario!!.recreate()
        waitFor("the same lesson after recreation", 5000) { has("explainer_stage") }
        assertEquals(last, cache(attention).session.conceptPosition)
        assertEquals("recreating the screen does not start the lesson again", messages, cache(attention).session.messages.size)
    }

    @Test fun a_started_walk_goes_to_the_requested_later_concept_instead_of_asking_for_its_earlier_lessons() {
        launch(); chooseGuest()
        awaitIo {
            val repo = deps.tutors
            val c = repo.open(attention).cache
            val draft = c.copy(draftAnswers = c.session.questions.associate { it.id to UNKNOWN_ANSWER }, focusChosen = true)
            val assessed = draft.copy(session = repo.assess(draft, "assessment"))
            repo.save(assessed.copy(session = repo.turn(assessed, TutorIntent.START, "", "first-lesson")))
        }
        selectLaterConcept()
        waitFor("the requested concept", 5000) { has("explainer_stage") }
        assertFalse(has("tutor_list"))
        sceneReady()
        val session = cache(attention).session
        assertEquals(session.questions.lastIndex, session.conceptPosition)
        assertEquals(session.questions.size, session.conceptsIntroduced)
        assertTrue("the first lesson was kept", session.messages.size > 2)
    }

    @Test fun shared_pdf_waits_for_a_selected_space_and_survives_rotation_before_confirmation() {
        val id = awaitIo { deps.profiles.choose(LocalProfile.GUEST); deps.spaces!!.create("Selected Space").spaceId }
        val file = File(app.cacheDir, "shared.pdf").apply { writeText("invalid PDF") }
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
        scenario = ActivityScenario.launch(intent)
        waitFor("destination selector", 5000) { has("share_spaces") }
        waitFor("selected destination loaded") { runCatching { rule.onNodeWithTag("share_spaces").performScrollToNode(hasText("Selected Space")); true }.getOrDefault(false) }
        tap(rule.onNodeWithText("Selected Space"))
        waitForTag("confirm_pdfs")
        scenario!!.recreate()
        waitForTag("confirm_pdfs")
        assertTrue(awaitIo { deps.pdfImports!!.list().isEmpty() })
        waitFor("restored confirmation enabled") { runCatching { rule.onNodeWithTag("confirm_pdfs").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("confirm_pdfs"))
        waitFor("one row owned by selected space") { awaitIo { deps.pdfImports!!.list().singleOrNull()?.spaceId == id } }
    }

    @Test fun shared_pdf_confirmation_stays_visible_in_a_previously_scrolled_space_and_after_rotation() {
        val title = "Research notes"
        val id = awaitIo {
            deps.profiles.choose(LocalProfile.GUEST)
            val spaces = requireNotNull(deps.spaces)
            val id = spaces.create(title).spaceId
            for (page in 1..20) spaces.add(id, SpaceMember.ServerDocumentRef("paper-$page", "1"))
            id
        }
        launch()
        openMaterial(title)
        rule.onNodeWithTag("space_materials").performScrollToNode(hasText("paper-20"))
        rule.onNodeWithText("paper-20").assertIsDisplayed()
        rule.onNodeWithTag("pick_pdfs").assertDoesNotExist()
        systemBack()
        waitForTag("home_SPACES")

        val file = File(app.cacheDir, "new-paper.pdf").apply { writeText("invalid PDF") }
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
        scenario!!.onActivity { activity ->
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, intent)
        }
        waitFor("destination list") { runCatching { rule.onNodeWithTag("share_spaces").performScrollToNode(hasText(title)); true }.getOrDefault(false) }
        tap(rule.onNodeWithText(title))
        fun assertOnePendingConfirmation() {
            waitFor("confirmation without scrolling", 5000) { has("confirm_pdfs") }
            rule.onAllNodes(isDialog()).assertCountEquals(1)
            rule.onNodeWithTag("confirm_pdfs").assertIsDisplayed()
            rule.onNodeWithText(file.name).assertIsDisplayed()
            assertTrue(awaitIo { deps.pdfImports!!.list().isEmpty() })
        }
        assertOnePendingConfirmation()
        scenario!!.recreate()
        assertOnePendingConfirmation()
        waitFor("confirmed destination ready") { runCatching { rule.onNodeWithTag("confirm_pdfs").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("confirm_pdfs"))
        waitFor("one imported row in the chosen Space") {
            awaitIo { deps.pdfImports!!.list().singleOrNull()?.let { it.spaceId == id && it.title == file.name } == true }
        }
    }

    @Test fun practice_only_source_opens_all_original_pages_and_returns_to_its_material() {
        launch(); chooseGuest()
        val pkg = Level("demo_mapreduce", com.anything2game.contract.FrameId.CLASSIC).pkg
        openMaterial(pkg.title)
        waitFor("full source action", 5000) { has("read_source_demo_mapreduce") }
        tap(rule.onNodeWithTag("read_source_demo_mapreduce"))
        waitForTag("material_reader")
        val last = pkg.sources.last()
        rule.onNodeWithTag("material_reader").performScrollToNode(hasText(last.text))
        rule.onNodeWithText(last.text).assertExists()
        systemBack(); waitForTag("space_materials")
    }

    @Test fun available_locker_numbers_have_readable_contrast_before_selecting_a_cut() {
        launch(); chooseGuest(); tap(rule.onNodeWithTag("tab_PLAYGROUND"))
        waitForTag("game_partition"); openGame(rule.onNodeWithTag("game_partition")); waitForTag("cut_1")
        val paper = CaseFileSkin.evidenceSoft.luminance()
        for (n in listOf("1","16")) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(n, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val ink = layouts.single().layoutInput.style.color.luminance()
            assertTrue("Locker $n needs 4.5:1 contrast", (maxOf(ink,paper)+.05f)/(minOf(ink,paper)+.05f) >= 4.5f)
        }
    }

    @Test fun exhausted_saved_game_can_start_a_fresh_attempt_without_claiming_a_transfer() {
        awaitIo {
            deps.profiles.choose(LocalProfile.GUEST)
            val spec = ReviewedGames.partition("en")
            val original = deps.learningProgress!!.start(spec, 52).checkpoint
            val exhausted = original.copy(actions = List(MechanicRegistry.MAX_ACTIONS) { GameAction.Help })
            val ledger = File(app.filesDir, "learning.json")
            val json = ContractJson.json
            val snapshot = json.parseToJsonElement(ledger.readText()).jsonObject
            ledger.writeText(JsonObject(snapshot + ("checkpoints" to JsonArray(listOf(json.encodeToJsonElement(exhausted))))).toString())
        }
        launch(); waitForTag("tab_PLAYGROUND"); tap(rule.onNodeWithTag("tab_PLAYGROUND"))
        waitForTag("game_partition"); openGame(rule.onNodeWithTag("game_partition"))
        waitFor("new attempt offered on exhausted checkpoint", 5000) { has("restart_game") }
        tap(rule.onNodeWithTag("restart_game"))
        waitFor("new attempt with no actions") { awaitIo { deps.learningProgress!!.checkpoint(ContentVersion("locker-search-en","1"))?.let { it.seed != 52L && it.actions.isEmpty() } == true } }
        assertTrue(awaitIo { deps.learningProgress!!.projection().days.isEmpty() })
    }
}
