package com.anything2game.app.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.anything2game.app.R
import com.anything2game.app.awaitIo
import com.anything2game.contract.FrameId
import com.anything2game.contract.game.ReviewedGames
import com.anything2game.contract.tutor.TutorIntent
import com.anything2game.contract.tutor.UNKNOWN_ANSWER
import com.anything2game.contract.tutor.Understanding
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The app speaks everyday language: no internal terms (mock, fixture, receipt, member, revision, verified, assessment, diagnostic,
 * facet, frame, session, pack, payload, endpoint, provider, ids and hashes ...) on any screen, in English or Chinese.
 *
 * Three nets: every string resource, the texts the code builds (the explainer's lead-ins and replies), and the real app driven
 * through its main screens with everything the student can read or hear checked. A course's own content (its title, its passages,
 * its questions) is the author's text, so it is left out of the check.
 */
class PlainLanguageGuardTest : LearnJourneyBase() {
    companion object {
        /** Internal words that must never reach a student. */
        val banned = mapOf(
            "en" to Regex("(?i)\\bmock|fixture|receipt|\\bmembers?\\b|revision|verified|\\bassess|diagnos|facet|\\bframes?\\b|\\bsessions?\\b|\\bpacks?\\b|\\bpackag|payload|endpoint|\\bprovider|\\bschema|\\bhash|fingerprint|\\bids?\\b|\\bmodel\\b|\\barchive|this build|content version|\\bidempoten|\\bmechanic|\\bboss\\b"),
            "zh" to Regex("(?i)mock|fixture|回执|成员|修订|验证|评估|诊断|摸底|测评|会话|内容包|学习包|数据包|载荷|端点|指纹|框架|切面|服务端|内容版本|令牌|构建"),
        )
        /** A raw error code or exception name on screen. */
        val rawCode = Regex("\\b[A-Z]{3,}(_[A-Z]+)+\\b|\\b[A-Za-z]+Exception\\b")
        fun offenders(texts: Collection<String>, language: String) = texts.filter { banned.getValue(language).containsMatchIn(it) || rawCode.containsMatchIn(it) }
    }

    // ---- every string resource ---------------------------------------------------------------------------

    @Test fun no_string_resource_uses_an_internal_word() {
        val res = File("src/main/res")
        val xml = res.resolve("values").listFiles { f -> f.extension == "xml" }.orEmpty().map { it.readText() }
        fun names(tag: String) = xml.flatMap { x -> Regex("<$tag\\s+name=\"([^\"]+)\"([^>]*)>").findAll(x).filter { "translatable=\"false\"" !in it.groupValues[2] }.map { it.groupValues[1] }.toList() }
        val strings = names("string"); val plurals = names("plurals"); val arrays = names("string-array")
        assertTrue("found the string resources", strings.size > 300 && plurals.isNotEmpty() && arrays.isNotEmpty())
        for (language in listOf("en", "zh")) {
            RuntimeEnvironment.setQualifiers(if (language == "zh") "+zh" else "+en")
            val r = app.resources
            fun id(type: String, name: String) = r.getIdentifier(name, type, app.packageName).also { check(it != 0) { "no $type/$name" } }
            val shown = strings.map { it to r.getString(id("string", it)) } +
                plurals.flatMap { n -> listOf(0, 1, 2, 5).map { q -> "$n($q)" to r.getQuantityString(id("plurals", n), q, q, q, q) } } +
                arrays.flatMap { n -> r.getStringArray(id("array", n)).map { n to it } }
            val bad = shown.filter { (k, v) -> k != "app_name" && offenders(listOf(v), language).isNotEmpty() }
            assertTrue("($language) internal words in string resources: $bad", bad.isEmpty())
        }
    }

    // ---- texts the code builds ---------------------------------------------------------------------------

    @Test fun the_explainers_own_sentences_are_plain() {
        val lines = mutableMapOf<String, MutableList<String>>("en" to mutableListOf(), "zh" to mutableListOf())
        for ((language, zh) in listOf("en" to false, "zh" to true)) {
            Understanding.entries.forEach { lines.getValue(language) += com.anything2game.contract.tutor.TutorRules.leadIn(it, zh) }
            val script = awaitIo { deps.tutorScripts.scriptFor(if (zh) zhSpaced else attention)!! }
            val session = com.anything2game.contract.tutor.TutorSession(sessionId = "g", documentId = "d", documentVersion = "v", title = "t", language = language, mode = "local-mock",
                questions = script.blueprint.concepts.map { it.question }, sources = emptyList(),
                assessment = script.blueprint.concepts.map { com.anything2game.contract.tutor.ConceptAssessment(it.question.id, it.question.conceptId, Understanding.NOT_YET_ASSESSED) })
            lines.getValue(language) += com.anything2game.contract.tutor.TutorRules.scriptedReply(session, script.blueprint, TutorIntent.QUESTION).text
        }
        for ((language, texts) in lines) assertTrue("($language) $texts", offenders(texts, language).isEmpty())
    }

    // ---- the real app, screen by screen ------------------------------------------------------------------

    /** Every course's own words: titles, passages, questions, explanations, route steps. */
    private val courseWords: String by lazy {
        norm(awaitIo {
            val packages = deps.packages.list().map { deps.packages.load(it.packageId).getOrThrow() }
            val scripts = packages.mapNotNull { deps.tutorScripts.scriptFor(it.packageId) }
            val nodes = deps.materials.materials()?.flatMap { it.nodes }.orEmpty()
            listOf(packages, scripts, nodes, deps.spaces.list().orEmpty(), ReviewedGames.all("en"), ReviewedGames.all("zh")).joinToString(" ") { it.toString() }
        })
    }
    private val courseTitles: List<String> by lazy {
        awaitIo {
            val packages = deps.packages.list().map { deps.packages.load(it.packageId).getOrThrow() }
            (packages.flatMap { listOf(it.title) + it.levels.map { l -> l.title } } +
                deps.materials.materials().orEmpty().flatMap { m -> listOf(m.title) + m.nodes.map { it.title } } +
                deps.spaces.list().orEmpty().map { it.title } +
                packages.mapNotNull { deps.tutorScripts.scriptFor(it.packageId) }.flatMap { s -> s.blueprint.concepts.map { it.question.concept } }).filter { it.length > 2 }.sortedByDescending { it.length }
        }
    }

    private fun assertPlain(where: String, language: String) {
        val own = everythingShown().filter { it.isNotBlank() }.filter { norm(it) !in courseWords }
            .map { text -> courseTitles.fold(text) { t, title -> t.replace(title, " ") } }
        assertTrue("$where ($language) shows nothing", everythingShown().size >= 3)
        val bad = offenders(own, language)
        assertTrue("$where ($language) uses internal words: $bad", bad.isEmpty())
    }

    @Test fun english_screens_use_plain_words() = screens("en", attention)
    @Test fun chinese_screens_use_plain_words() = screens("zh", zhSpaced)

    /** Welcome, Spaces, a Space's map and step, its materials, the Me tab, then a case played to its recap. */
    private fun screens(language: String, pkg: String) {
        RuntimeEnvironment.setQualifiers(if (language == "zh") "+zh" else "+en")
        launch(); waitForTag("welcome")
        assertPlain("welcome", language)
        chooseLin()
        assertPlain("Spaces list", language)

        val (spaceId, nodeId) = awaitIo {
            val mat = deps.materials.materials().single { it.localPack?.packageId == pkg }
            deps.spaces.list().first { mat.member in it.members }.spaceId to mat.nodes.first().nodeId
        }
        waitFor("the Space card") { runCatching { rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag("open_space_$spaceId")); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("open_space_$spaceId"))
        waitFor("the route map") { runCatching { rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_$nodeId")); true }.getOrDefault(false) }
        assertPlain("the map", language)
        rule.onNodeWithTag("space_route").performScrollToNode(hasTestTag("route_node_$nodeId")); rule.waitForIdle()
        tap(rule.onNodeWithTag("route_node_$nodeId"))
        waitForTag("route_sheet")
        assertPlain("a step on the map", language)
        tap(rule.onNodeWithTag("route_sheet_close"))
        tap(rule.onNodeWithTag("show_materials"))
        waitForTag("space_materials")
        assertPlain("materials", language)
        waitFor("the file's menu") { runCatching { rule.onNodeWithTag("space_materials").performScrollToNode(hasTestTag("material_more_$pkg")); true }.getOrDefault(false) }
        tap(rule.onNodeWithTag("material_more_$pkg"))
        assertPlain("a file's menu", language)
        tap(rule.onNodeWithTag("material_more_$pkg"))

        openMe()
        assertPlain("Me", language)
        scrollMeTo(str(R.string.home_stats_note))
        assertPlain("Me, figures", language)
        waitFor("demo controls") { runCatching { rule.onNodeWithTag("home_PROFILE").performScrollToNode(androidx.compose.ui.test.hasText(str(R.string.home_demo_tools))); true }.getOrDefault(false) }
        assertPlain("Me, the end", language)

        openPractice()
        val title = Level(pkg, FrameId.CASE).pkg.title
        openMaterial(title); unfoldPractice(title)
        assertPlain("the practice list", language)
        playAs(title, FrameId.CASE)
        assertPlain("play as", language)
        startLevel(title, Level(pkg, FrameId.CASE).pkg.levels.first().title)
        val level = Level(pkg, FrameId.CASE)
        level.step()
        assertPlain("the case briefing", language)
        level.playUntil("the first clue answered") { it.results.isNotEmpty() }
        assertPlain("a verdict", language)
        level.playUntil("the cross-examination") { it.currentSlot?.kind == com.anything2game.engine.session.SlotKind.BOSS }
        waitForText(str(R.string.crossexam_testimony))
        assertPlain("the cross-examination", language)
        level.play()
        waitForText(str(R.string.stamp_case_closed))
        assertPlain("the recap", language)
    }

    @Test fun english_explainer_uses_plain_words() = explainer("en", attention)
    @Test fun chinese_explainer_uses_plain_words() = explainer("zh", zhSpaced)

    /** The few quick questions, where you are now, the conversation, its history, the ideas list and the wrap-up. */
    private fun explainer(language: String, pkg: String) {
        RuntimeEnvironment.setQualifiers(if (language == "zh") "+zh" else "+en")
        launch(); waitForTag("welcome"); chooseGuest(); openLearn()
        startExplanation(pkg)
        val total = cache(pkg).session.questions.size
        waitFor("the focus step or the first question") { textShown(str(R.string.focus_title)) || textShown(str(R.string.tutor_question_count, 1, total)) }
        if (textShown(str(R.string.focus_title))) { assertPlain("the focus step", language); chooseFocus("focus_result") }
        runDiagnostic(pkg) { _ -> assertPlain("a quick question", language); UNKNOWN_ANSWER }
        assertPlain("where you are now", language)
        startConversation()
        readPages("the explainer", onPage = { _, _ -> assertPlain("an explainer page", language) })
        assertPlain("the choice bar", language)
        choose(pkg, "tutor_example")
        readPages("the example", onPage = { _, _ -> assertPlain("an example page", language) })
        tap(rule.onNodeWithTag("tutor_ask"))
        waitForTag("tutor_input")
        assertPlain("the ask sheet", language)
    }
}
