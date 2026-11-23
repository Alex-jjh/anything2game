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
import com.anything2game.app.MainActivity
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.contract.space.*
import com.anything2game.contract.tutor.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReviewedSpaceJourneysTest : LearnJourneyBase() {
    private fun selectLaterConcept() {
        val (space, node) = runBlocking {
            val mat = deps.materials!!.materials().single { it.localPack?.packageId == attention }
            val parent = deps.spaces!!.list().first { mat.member in it.members }
            parent to mat.nodes.filter { it.activity is ActivityRef.Tutor }.last()
        }
        waitFor("parent Space card") { runCatching { rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag("open_space_${space.spaceId}")); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("open_space_${space.spaceId}"))
        waitForTag("show_route")
        val tag = "open_node_${node.nodeId}"
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag(tag))
        waitFor("node enabled") { runCatching { rule.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag(tag))
    }

    @Test fun requested_later_concept_survives_recreation_and_diagnostic_without_silently_crediting_another_lesson() {
        launch(); chooseGuest(); waitForTag("space_create")
        selectLaterConcept()
        waitFor("requested concept before diagnosis", 5000) { has("route_prerequisite") }
        tap(rule.onNodeWithTag("route_follow"))
        chooseFocus()
        scenario!!.recreate()
        val questions = cache(attention).session.questions
        for (q in questions) answerCurrent(q, UNKNOWN_ANSWER)
        waitForTag("route_prerequisite")
        assertTrue(textOf("route_prerequisite").contains(questions.last().concept))
        assertTrue(textOf("route_prerequisite").contains(questions.first().concept))
        assertTrue(runBlocking { deps.learningProgress!!.projection().completed.isEmpty() })
        tap(rule.onNodeWithTag("route_follow"))
        waitForTag("dialogue_text")
        assertEquals(questions.first().conceptId, TutorRules.orderedQuestions(cache(attention).session).first().conceptId)
    }

    @Test fun partially_read_route_explains_the_unintroduced_target_before_opening_its_prerequisite() {
        launch(); chooseGuest()
        runBlocking {
            val repo = deps.tutors
            val c = repo.open(attention).cache
            val draft = c.copy(draftAnswers = c.session.questions.associate { it.id to UNKNOWN_ANSWER }, focusChosen = true)
            val assessed = draft.copy(session = repo.assess(draft, "assessment"))
            repo.save(assessed.copy(session = repo.turn(assessed, TutorIntent.START, "", "first-lesson")))
        }
        selectLaterConcept()
        waitFor("actual prerequisite", 5000) { has("route_prerequisite") }
        val session = cache(attention).session
        assertTrue(textOf("route_prerequisite").contains(session.questions.last().concept))
        assertTrue(textOf("route_prerequisite").contains(session.questions.first().concept))
        assertFalse(has("dialogue_text"))
        tap(rule.onNodeWithTag("route_follow"))
        waitForTag("dialogue_text")
        assertEquals(1, cache(attention).session.conceptsIntroduced)
    }

    @Test fun shared_pdf_waits_for_a_selected_space_and_survives_rotation_before_confirmation() {
        val id = runBlocking { deps.profiles.choose(LocalProfile.GUEST); deps.spaces!!.create("Selected Space").spaceId }
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
        assertTrue(runBlocking { deps.pdfImports!!.list().isEmpty() })
        waitFor("restored confirmation enabled") { runCatching { rule.onNodeWithTag("confirm_pdfs").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("confirm_pdfs"))
        waitFor("one row owned by selected space") { runBlocking { deps.pdfImports!!.list().singleOrNull()?.spaceId == id } }
    }

    @Test fun shared_pdf_confirmation_stays_visible_in_a_previously_scrolled_space_and_after_rotation() {
        val title = "Research notes"
        val id = runBlocking {
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
            assertTrue(runBlocking { deps.pdfImports!!.list().isEmpty() })
        }
        assertOnePendingConfirmation()
        scenario!!.recreate()
        assertOnePendingConfirmation()
        waitFor("confirmed destination ready") { runCatching { rule.onNodeWithTag("confirm_pdfs").assertIsEnabled(); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("confirm_pdfs"))
        waitFor("one imported row in the chosen Space") {
            runBlocking { deps.pdfImports!!.list().singleOrNull()?.let { it.spaceId == id && it.title == file.name } == true }
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
        waitForTag("game_partition"); tap(rule.onNodeWithTag("game_partition")); waitForTag("cut_1")
        val paper = CaseFileSkin.evidenceSoft.luminance()
        for (n in listOf("1","16")) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(n, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val ink = layouts.single().layoutInput.style.color.luminance()
            assertTrue("Locker $n needs 4.5:1 contrast", (maxOf(ink,paper)+.05f)/(minOf(ink,paper)+.05f) >= 4.5f)
        }
    }

    @Test fun exhausted_saved_game_can_start_a_fresh_attempt_without_claiming_a_transfer() {
        runBlocking {
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
        waitForTag("game_partition"); tap(rule.onNodeWithTag("game_partition"))
        waitFor("new attempt offered on exhausted checkpoint", 5000) { has("restart_game") }
        tap(rule.onNodeWithTag("restart_game"))
        waitFor("new attempt with no actions") { runBlocking { deps.learningProgress!!.checkpoint(ContentVersion("locker-search-en","1"))?.let { it.seed != 52L && it.actions.isEmpty() } == true } }
        assertTrue(runBlocking { deps.learningProgress!!.projection().days.isEmpty() })
    }
}
