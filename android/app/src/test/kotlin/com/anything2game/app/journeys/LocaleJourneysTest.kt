package com.anything2game.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import com.anything2game.app.R
import com.anything2game.contract.FrameId
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SlotKind
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Journey 7: a Chinese-locale phone. */
class LocaleJourneysTest : JourneyTest() {
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    /** Every string a student can read right now: no untranslated English UI, no raw format placeholders or resource names. */
    private fun assertScreenIsTidy(where: String) {
        val texts = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any text") { true }).fetchSemanticsNodes().flatMap { n ->
            (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()) + listOfNotNull(n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
        }
        texts.forEach { t ->
            assertFalse("raw placeholder on $where: \"$t\"", Regex("%\\d?\\$?[sd]|@string|R\\.string").containsMatchIn(t))
        }
    }

    @Test fun every_english_string_has_a_chinese_twin_with_the_same_placeholders() {
        val res = File("src/main/res")
        fun read(dir: String) = res.resolve(dir).listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { f ->
            Regex("<string\\s+name=\"([^\"]+)\"([^>]*)>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
        }
        val en = read("values").filter { "translatable=\"false\"" !in it.second }.associate { it.first to it.third }
        val zh = read("values-zh").associate { it.first to it.third }
        val ph = Regex("%(\\d+\\$)?[sd]")
        assertEquals("missing zh strings", emptyList<String>(), en.keys.filter { it !in zh })
        en.forEach { (k, v) ->
            assertEquals("placeholders of $k differ between en and zh", ph.findAll(v).map { it.value }.sorted().toList(), ph.findAll(zh.getValue(k)).map { it.value }.sorted().toList())
            if (Regex("[A-Za-z]{4,}").containsMatchIn(v.replace(Regex("%\\d?\\$?[sd]"), "")))
                assertTrue("zh string $k is still English: ${zh.getValue(k)}", cjk.containsMatchIn(zh.getValue(k)))
        }
    }

    @Test fun a_chinese_phone_can_go_from_welcome_to_case_closed_in_chinese() {
        RuntimeEnvironment.setQualifiers("+zh")
        launch()
        waitForTag("welcome")
        assertTrue("welcome is in Chinese", cjk.containsMatchIn(str(R.string.profile_sample_action)))
        waitForText(str(R.string.profile_sample_action))
        assertScreenIsTidy("welcome")
        chooseLin()
        openMe()
        waitForText(str(R.string.sample_profile_name))
        assertScreenIsTidy("Me tab")

        openPractice()
        val title = Level("demo_zh_spaced", FrameId.CASE).pkg.title
        openMaterial(title)
        assertScreenIsTidy("Practice")
        playAs(title, FrameId.CASE)
        waitForText(str(R.string.frame_case_desc))
        val level1 = Level("demo_zh_spaced", FrameId.CASE).pkg.levels.first().title
        startLevel(title, level1)
        val level = Level("demo_zh_spaced", FrameId.CASE)
        assertEquals(FrameId.CASE, level.session()!!.frameId)

        level.step()                              // briefing
        assertScreenIsTidy("scene")
        level.playUntil("the first clue answered") { it.results.isNotEmpty() }   // verdict on screen
        assertScreenIsTidy("verdict")
        waitFor("a verdict stamp") { textShown(str(R.string.stamp_correct)) || textShown(str(R.string.stamp_wrong)) || textShown(str(R.string.stamp_partly)) }
        level.playUntil("the cross-examination") { it.currentSlot?.kind == SlotKind.BOSS }
        waitForText(str(R.string.crossexam_testimony))
        assertScreenIsTidy("cross-examination")
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
        waitForText(str(R.string.stamp_case_closed))
        assertScreenIsTidy("recap")
        assertTrue(cjk.containsMatchIn(str(R.string.recap_library)))
        tapText(str(R.string.recap_library))
        waitForTag("space_materials")
    }
}
