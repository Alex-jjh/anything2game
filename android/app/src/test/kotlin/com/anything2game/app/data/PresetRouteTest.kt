package com.anything2game.app.data

import androidx.test.core.app.ApplicationProvider
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.ui.space.cleanTitle
import com.anything2game.contract.space.ActivityRef
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
                val chapter = nodes.filter { it.chapter == level.title }
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
}
