package com.anything2game.app.ui.space

import com.anything2game.contract.FrameId
import com.anything2game.contract.game.EvidenceRef
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.space.LearningPath
import com.anything2game.contract.space.PathNode
import com.anything2game.contract.space.Space
import com.anything2game.engine.progress.NodeStatus
import com.anything2game.engine.progress.Standing
import org.junit.Assert.*
import org.junit.Test

class RouteMapTest {
    private fun tutor(id: String, concept: String) = ActivityRef.Tutor("doc", "v1", concept, sessionId = "s")
    private fun practice(level: String, frame: FrameId = FrameId.CLASSIC) = ActivityRef.Practice("pack", "v1", level, frame)
    private fun node(id: String, title: String, activity: ActivityRef, after: String? = null, chapter: String? = null, evidence: List<EvidenceRef> = emptyList()) =
        PathNode(id, title, activity, listOfNotNull(after), evidence = evidence, chapter = chapter)
    private fun path(vararg nodes: PathNode) = LearningPath("p", "1", "space", "Space", "en", nodes.toList())
    /** The standing a status implies, for tests that only care about the route's own states. */
    private fun standings(status: Map<String, NodeStatus>) = status.mapValues { (_, s) ->
        when (s) { NodeStatus.COMPLETE -> Standing.DONE; NodeStatus.REVIEW -> Standing.DUE; else -> Standing.NEW }
    }
    private fun map(path: LearningPath, status: Map<String, NodeStatus>, material: (PathNode) -> MaterialInfo? = { null }) =
        routeMap(path, status, standings(status), material) { _, _ -> null }

    private val chain = path(
        node("a", "Why ditch recurrence", tutor("a", "c1"), chapter = "Level one"),
        node("b", "Level one", practice("L1", FrameId.CASE), "a", chapter = "Level one"),
        node("c", "Multi-head attention", tutor("c", "c2"), "b", chapter = "Level two"),
        node("d", "Level two", practice("L2"), "c", chapter = "Level two"),
    )

    @Test fun nodes_group_into_sections_by_chapter_and_count_what_is_done() {
        val m = map(chain, mapOf("a" to NodeStatus.COMPLETE, "b" to NodeStatus.AVAILABLE, "c" to NodeStatus.LOCKED, "d" to NodeStatus.LOCKED))
        assertEquals(listOf("Level one", "Level two"), m.sections.map { it.title })
        assertEquals(listOf(1, 2), m.sections.map { it.number })
        assertEquals(listOf(2, 2), m.sections.map { it.steps.size })
        assertEquals(listOf(1, 0), m.sections.map { it.done })
        assertEquals(1, m.done)
        assertEquals(4, m.total)
        assertEquals(listOf(1, 2, 3, 4), m.steps.map { it.number })
    }

    @Test fun the_first_open_step_is_current_and_later_open_steps_are_only_available() {
        val m = map(chain, mapOf("a" to NodeStatus.COMPLETE, "b" to NodeStatus.AVAILABLE, "c" to NodeStatus.AVAILABLE, "d" to NodeStatus.LOCKED))
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.AVAILABLE, StepState.LOCKED), m.steps.map { it.state })
        assertEquals("b", m.current?.id)
    }

    @Test fun a_locked_step_names_the_first_unfinished_step_it_waits_for() {
        val m = map(chain, mapOf("a" to NodeStatus.COMPLETE, "b" to NodeStatus.AVAILABLE, "c" to NodeStatus.LOCKED, "d" to NodeStatus.LOCKED))
        assertEquals("Level one", m.steps[2].waitingFor)
        assertEquals("Multi-head attention", m.steps[3].waitingFor)
        assertNull(m.steps[1].waitingFor)
    }

    @Test fun a_due_review_is_current_only_when_nothing_new_is_open() {
        val open = map(chain, mapOf("a" to NodeStatus.REVIEW, "b" to NodeStatus.AVAILABLE, "c" to NodeStatus.LOCKED, "d" to NodeStatus.LOCKED))
        assertEquals(listOf(StepState.REVIEW, StepState.CURRENT, StepState.LOCKED, StepState.LOCKED), open.steps.map { it.state })
        assertEquals(1, open.done)
        val finished = map(chain, mapOf("a" to NodeStatus.COMPLETE, "b" to NodeStatus.REVIEW, "c" to NodeStatus.COMPLETE, "d" to NodeStatus.COMPLETE))
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.DONE, StepState.DONE), finished.steps.map { it.state })
        assertEquals(4, finished.done)
    }

    @Test fun nothing_is_current_when_everything_is_done() {
        val m = map(chain, chain.nodes.associate { it.nodeId to NodeStatus.COMPLETE })
        assertNull(m.current)
        assertEquals(m.total, m.done)
    }

    @Test fun a_step_that_needs_an_update_is_not_openable_and_a_missing_status_counts_as_one() {
        val m = map(chain, mapOf("a" to NodeStatus.NEEDS_UPDATE))
        assertEquals(StepState.UPDATE, m.steps[0].state)
        assertEquals(StepState.UPDATE, m.steps[3].state)
        assertNull(m.current)
    }

    @Test fun step_kind_follows_the_activity_and_the_frame_the_practice_opens_in() {
        assertEquals(StepKind.EXPLAIN, kindOf(tutor("a", "c")))
        assertEquals(StepKind.CLASSIC, kindOf(practice("L")))
        assertEquals(StepKind.CASE, kindOf(practice("L", FrameId.CASE)))
        assertEquals(StepKind.ROOKIE, kindOf(practice("L", FrameId.ROOKIE)))
        assertEquals(StepKind.ESCAPE, kindOf(practice("L", FrameId.ESCAPE)))
        assertEquals(StepKind.GAME, kindOf(ActivityRef.Game("g", "1", "final")))
    }

    @Test fun nodes_without_a_chapter_group_by_material_and_name_it_only_when_materials_are_mixed() {
        val mixed = path(
            node("a", "One", tutor("a", "c1")), node("b", "Two", tutor("b", "c2"), "a"),
            node("c", "Three", tutor("c", "c3")),
        )
        val material = { n: PathNode -> MaterialInfo(if (n.nodeId == "c") "Second paper" else "First paper", fixture = n.nodeId == "c") }
        val status = mixed.nodes.associate { it.nodeId to NodeStatus.AVAILABLE }
        val m = map(mixed, status, material)
        assertEquals(listOf("First paper", "Second paper"), m.sections.map { it.title })
        assertEquals(listOf<String?>(null, null), m.sections.map { it.material })
        assertEquals(listOf(false, true), m.sections.map { it.fixture })
        val withChapter = mixed.copy(nodes = mixed.nodes.map { it.copy(chapter = "Basics") })
        assertEquals(1, map(withChapter, status, material).sections.size)
        assertEquals("First paper", map(withChapter, status, material).sections.single().material)
    }

    @Test fun generator_labels_are_removed_from_titles_and_the_section_carries_the_badge() {
        assertEquals("基础内容 1", cleanTitle("基础内容 1（mock）"))
        assertEquals("Foundation 1", cleanTitle("Foundation 1 (mock)"))
        assertEquals("Mockingbird", cleanTitle("Mockingbird"))
        val path = path(node("a", "Foundation 1 (mock)", tutor("a", "c1")))
        val m = map(path, mapOf("a" to NodeStatus.AVAILABLE)) { MaterialInfo("Notes", fixture = true) }
        assertEquals("Foundation 1", m.steps.single().title)
        assertTrue(m.sections.single().fixture)
    }

    @Test fun a_step_with_a_cited_page_carries_its_source() {
        val cite = EvidenceRef.Pdf("e1", "doc", "v1", "c1", "quote", 4, "5")
        val path = path(node("a", "Cited", tutor("a", "c1"), evidence = listOf(cite)), node("b", "Plain", tutor("b", "c2"), "a"))
        val status = path.nodes.associate { it.nodeId to NodeStatus.AVAILABLE }
        val m = routeMap(path, status, standings(status), { null }) { _, _ -> "paper.pdf" }
        assertEquals("5", m.steps[0].source?.page)
        assertEquals("paper.pdf", m.steps[0].source?.material)
        assertNull(m.steps[1].source)
    }

    @Test fun a_step_carries_the_standing_it_was_given_and_the_card_reads_the_same_map() {
        val status = mapOf("a" to NodeStatus.COMPLETE, "b" to NodeStatus.AVAILABLE, "c" to NodeStatus.LOCKED, "d" to NodeStatus.LOCKED)
        val m = routeMap(chain, status, mapOf("a" to Standing.LEARNED, "b" to Standing.NEEDS_PRACTICE), { null }) { _, _ -> null }
        assertEquals(listOf(Standing.LEARNED, Standing.NEEDS_PRACTICE, Standing.NEW, Standing.NEW), m.steps.map { it.standing })
        val card = SpaceCard(Space("s", "Space", 0), chain, m)
        assertEquals("b", card.next?.nodeId)
        assertEquals(m.done, card.completed)
        assertEquals(m.total, card.total)
        assertEquals(0, SpaceCard(Space("s", "Space", 0), null, null).total)
    }
}
