package com.anything2game.app.data

import androidx.test.core.app.ApplicationProvider
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.ui.space.cleanTitle
import com.anything2game.contract.space.ActivityRef
import com.anything2game.engine.game.completionActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The demo courses become routes from their own levels and concepts: real titles, one chapter per level, a single chain of steps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetRouteTest {
    private val deps = AndroidAppContainer(ApplicationProvider.getApplicationContext())
    private fun material(pkg: String) = runBlocking {
        deps.profiles.choose(LocalProfile.GUEST)
        deps.materials!!.materials().single { it.localPack?.packageId == pkg }
    }
    private fun levels(pkg: String) = runBlocking { deps.packages.load(pkg).getOrThrow().levels }

    @Test fun every_chapter_is_a_level_and_ends_with_that_levels_practice() {
        for (pkg in listOf("demo_attention", "demo_resnet", "demo_mapreduce", "demo_cs231n_l1", "demo_zh_spaced")) {
            val nodes = material(pkg).nodes
            val levels = levels(pkg)
            assertEquals(pkg, levels.map { it.title }, nodes.mapNotNull { it.chapter }.distinct())
            levels.forEach { level ->
                // A related game, where the course has one, follows the level's practice inside the same chapter.
                val chapter = nodes.filter { it.chapter == level.title && it.activity !is ActivityRef.Game }
                val last = chapter.last().activity as ActivityRef.Practice
                assertEquals(level.levelId, last.activityId)
                assertTrue(chapter.dropLast(1).all { it.activity is ActivityRef.Tutor })
            }
        }
    }

    @Test fun concepts_keep_their_order_and_land_in_the_level_that_cites_their_passage() {
        val nodes = material("demo_attention").nodes
        val chapters = nodes.filter { it.activity is ActivityRef.Tutor }.associate { it.title to it.chapter }
        assertEquals("Why the Transformer Replaces Recurrence", chapters["Why ditch recurrence"])
        assertEquals("How Attention Actually Works", chapters["Multi-head attention"])
        assertEquals("Results, Variations and Generalization", chapters["Translation results"])
        assertEquals(listOf("Why ditch recurrence", "Multi-head attention", "Translation results", "Local attention trade-off"),
            nodes.filter { it.activity is ActivityRef.Tutor }.map { it.title })
    }

    @Test fun steps_form_one_chain_and_nothing_says_mock() {
        for (pkg in listOf("demo_attention", "demo_resnet", "demo_zh_spaced", "demo_mapreduce")) {
            val nodes = material(pkg).nodes
            assertTrue(nodes.first().prerequisites.isEmpty())
            nodes.zipWithNext().forEach { (before, after) -> assertEquals(listOf(before.nodeId), after.prerequisites) }
            assertEquals(nodes.map { it.nodeId }.size, nodes.map { it.nodeId }.toSet().size)
            nodes.forEach { assertEquals(it.title, cleanTitle(it.title)); assertFalse(it.title.contains("mock", ignoreCase = true)) }
        }
    }

    @Test fun a_course_without_a_tutor_script_is_just_its_levels() {
        val nodes = material("demo_mapreduce").nodes
        assertTrue(nodes.all { it.activity is ActivityRef.Practice })
        assertEquals(levels("demo_mapreduce").map { it.title }, nodes.map { it.title })
    }

    @Test fun the_practice_step_opens_in_the_frame_the_player_prefers() {
        runBlocking { deps.profiles.choose(LocalProfile.GUEST) }
        deps.framePreferences.set("demo_attention", com.anything2game.contract.FrameId.CASE)
        val practice = runBlocking { deps.materials!!.materials().single { it.localPack?.packageId == "demo_attention" } }.nodes.map { it.activity }.filterIsInstance<ActivityRef.Practice>()
        assertTrue(practice.all { it.frame == com.anything2game.contract.FrameId.CASE })
    }

    @Test fun the_sample_student_arrives_with_a_lived_in_map() {
        val statuses = runBlocking {
            deps.profiles.choose(LocalProfile.SAMPLE)
            val material = deps.materials!!.materials().single { it.localPack?.packageId == "demo_attention" }
            val path = deps.spaces!!.route(deps.spaces!!.list().first { material.member in it.members }, "en", listOf(material))!!
            deps.materials!!.nodeStatuses(path, deps.learningProgress!!, System.currentTimeMillis())
        }
        val done = statuses.values.count { it == com.anything2game.engine.progress.NodeStatus.COMPLETE || it == com.anything2game.engine.progress.NodeStatus.REVIEW }
        assertEquals(2, done)
        assertEquals(1, statuses.values.count { it == com.anything2game.engine.progress.NodeStatus.AVAILABLE })
        assertTrue(statuses.values.any { it == com.anything2game.engine.progress.NodeStatus.LOCKED })
    }

    @Test fun a_related_playground_game_follows_the_unit_it_relates_to() {
        val expected = mapOf("demo_resnet" to ("layer-fault" to 0), "demo_attention" to ("gpu-budget" to 2),
            "pkg_demo_retrieval" to ("exam-revision" to 0), "demo_zh_spaced" to ("exam-revision" to 0))
        for ((pkg, want) in expected) {
            val nodes = material(pkg).nodes
            val games = nodes.filter { it.activity is ActivityRef.Game }
            assertEquals("$pkg has exactly one game step", 1, games.size)
            val game = games.single(); val ref = game.activity as ActivityRef.Game
            assertTrue("$pkg: ${ref.contentId}", ref.contentId.startsWith(want.first + "-"))
            assertEquals(levels(pkg).first().let { if (pkg == "demo_zh_spaced") "zh" else "en" }, ref.contentId.substringAfterLast('-'))
            // Credited by the game's completion key, the one the Playground records, so finishing it there or here is the same.
            val spec = com.anything2game.contract.game.ReviewedGames.all(ref.contentId.substringAfterLast('-')).single { it.gameId == ref.contentId }
            assertEquals(spec.completionActivity(), ref)
            assertEquals(spec.title, game.title)
            // Right after the practice of the unit it relates to, in that unit's chapter, and the chain stays one line.
            val at = nodes.indexOf(game)
            assertEquals(levels(pkg)[want.second].title, game.chapter)
            assertEquals(levels(pkg)[want.second].levelId, (nodes[at - 1].activity as ActivityRef.Practice).activityId)
            assertEquals(listOf(nodes[at - 1].nodeId), game.prerequisites)
            nodes.getOrNull(at + 1)?.let { assertEquals(listOf(game.nodeId), it.prerequisites) }
        }
        for (pkg in listOf("demo_mapreduce", "demo_cs231n_l1")) assertTrue("$pkg has no game that fits", material(pkg).nodes.none { it.activity is ActivityRef.Game })
    }

    @Test fun finishing_the_game_from_the_playground_credits_its_route_step_and_the_sample_map_still_opens_part_way() {
        val progress = runBlocking {
            deps.profiles.choose(LocalProfile.SAMPLE)
            val material = deps.materials!!.materials().single { it.localPack?.packageId == "demo_resnet" }
            val path = deps.spaces!!.route(deps.spaces!!.list().first { material.member in it.members }, "en", listOf(material))!!
            val game = path.nodes.single { it.activity is ActivityRef.Game }
            val before = deps.materials!!.nodeStatuses(path, deps.learningProgress!!, System.currentTimeMillis())
            assertEquals("the map opens part-way: the game waits behind the half-played practice", com.anything2game.engine.progress.NodeStatus.LOCKED, before.getValue(game.nodeId))
            assertEquals(1, before.values.count { it == com.anything2game.engine.progress.NodeStatus.AVAILABLE })
            deps.learningProgress!!.record(game.activity, com.anything2game.contract.progress.ActivityOutcome.INDEPENDENT, System.currentTimeMillis())
            deps.materials!!.nodeStatuses(path, deps.learningProgress!!, System.currentTimeMillis()).getValue(game.nodeId)
        }
        assertEquals(com.anything2game.engine.progress.NodeStatus.COMPLETE, progress)
    }
}
