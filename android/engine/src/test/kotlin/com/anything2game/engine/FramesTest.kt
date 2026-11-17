package com.anything2game.engine

import com.anything2game.contract.ContractJson
import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.contract.SwipeItem
import com.anything2game.contract.DeduceItem
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.CrossExamAnswer
import com.anything2game.engine.grading.CrossExamGrader
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.OrderAnswer
import com.anything2game.engine.grading.SortAnswer
import com.anything2game.engine.grading.SwipeAnswer
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SessionCodec
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FramesTest {
    private val dir = File(System.getProperty("contract.examples")!!)
    private val pkg = Fixtures.pkg
    private fun overlay(frame: String): FrameOverlay = ContractJson.decodeFrame(File(dir, "frame.v1.retrieval.$frame.json").readText())
    private fun session(id: FrameId) = LevelSession(
        pkg, GraderRegistry.default(), LeitnerPolicy(), Clock { 0 },
        frame = Frames.of(id), overlay = if (id == FrameId.CLASSIC) null else overlay(id.name.lowercase()),
    )

    /** A correct answer for whatever item is current. */
    private fun right(s: LevelSession, st: SessionState): Answer = when (val item = s.item(st)) {
        is SwipeItem -> SwipeAnswer(item.cards.associate { it.id to it.supported })
        is SortItem -> SortAnswer(item.cards.associate { it.id to it.binId })
        is DeduceItem -> DeduceAnswer(item.options.single { it.correct }.id)
        is OrderItem -> OrderAnswer(item.steps.map { it.id })
        is CrossExamItem -> item.statements.single { it.flawed }.let { CrossExamAnswer(it.id, it.sourceRefs.first().chunkId) }
        null -> error("no item")
    }

    /** Plays the level perfectly and returns the kinds of slots visited. */
    private fun playThrough(id: FrameId): Pair<List<SlotKind>, SessionState> {
        val s = session(id)
        var st = s.start("L1")
        val kinds = mutableListOf<SlotKind>()
        while (!st.isFinished) {
            kinds += st.currentSlot!!.kind
            st = if (st.currentSlot!!.kind == SlotKind.SCENE) s.reduce(st, Action.Next).state
            else s.reduce(s.reduce(st, Action.Submit(right(s, st))).state, Action.Next).state
        }
        return kinds to st
    }

    @Test fun `classic is units only, no stakes`() {
        val (kinds, end) = playThrough(FrameId.CLASSIC)
        assertEquals(List(3) { SlotKind.UNIT }, kinds)
        assertNull(end.credibility)
        assertNull(session(FrameId.CLASSIC).recap(end).bossCleared)
    }

    @Test fun `case is intro, units, cross-exam boss, outro, with credibility 3`() {
        val (kinds, end) = playThrough(FrameId.CASE)
        assertEquals(listOf(SlotKind.SCENE, SlotKind.UNIT, SlotKind.UNIT, SlotKind.UNIT, SlotKind.BOSS, SlotKind.SCENE), kinds)
        assertEquals(Phase.Recap, end.phase)
        assertEquals(3, end.credibility)
        assertEquals(true, session(FrameId.CASE).recap(end).bossCleared)
    }

    @Test fun `escape opens each room before its units and ends with the lock`() {
        val s = session(FrameId.ESCAPE)
        val q = s.start("L1").queue
        assertEquals(listOf("intro", "room:r1", null, "room:r2", null, "room:r3", null, null, "outro"), q.map { it.scene })
        assertEquals(SlotKind.BOSS, q[7].kind)
        assertEquals(Phase.Recap, playThrough(FrameId.ESCAPE).second.phase)
    }

    @Test fun `rookie and companion put a lead-in scene before every unit`() {
        listOf(FrameId.ROOKIE, FrameId.COMPANION).forEach { id ->
            val q = session(id).start("L1").queue
            pkg.levels[0].units.forEach { u ->
                val i = q.indexOfFirst { it.kind == SlotKind.UNIT && it.unitId == u.unitId }
                assertEquals("$id ${u.unitId}", "lead:${u.unitId}", q[i - 1].scene)
            }
        }
        assertEquals(SlotKind.BOSS, session(FrameId.ROOKIE).start("L1").queue.dropLast(1).last().kind)
        assertFalse(session(FrameId.COMPANION).start("L1").queue.any { it.kind == SlotKind.BOSS })
    }

    @Test fun `scene lines resolve from the overlay`() {
        val s = session(FrameId.CASE)
        val st = s.start("L1")
        assertEquals("mira", s.sceneLines(st)!!.first().speaker)
        assertNull(s.item(st))
    }

    @Test fun `three mistakes make the case go cold`() {
        val s = session(FrameId.CASE)
        var st = s.reduce(s.start("L1"), Action.Next).state // past intro
        repeat(3) {
            st = s.reduce(st, Action.Submit(SwipeAnswer(emptyMap()).takeIf { s.item(st) is SwipeItem } ?: wrong(s, st))).state
            st = s.reduce(st, Action.Next).state
        }
        assertEquals(Phase.Failed, st.phase)
        assertEquals(0, st.credibility)
        assertTrue(st.isFinished)
    }

    private fun wrong(s: LevelSession, st: SessionState): Answer = when (val item = s.item(st)) {
        is SortItem -> SortAnswer(emptyMap())
        is DeduceItem -> DeduceAnswer(item.options.first { !it.correct }.id)
        is SwipeItem -> SwipeAnswer(emptyMap())
        else -> error("unexpected $item")
    }

    @Test fun `retries land before the boss`() {
        val s = session(FrameId.CASE)
        var st = s.reduce(s.start("L1"), Action.Next).state
        st = s.reduce(st, Action.Next).state.let { s.reduce(st, Action.Submit(SwipeAnswer(emptyMap()))).state }
        val bossAt = st.queue.indexOfFirst { it.kind == SlotKind.BOSS }
        val retryAt = st.queue.indexOfFirst { it.retry }
        assertTrue("retry $retryAt before boss $bossAt", retryAt in 0 until bossAt)
    }

    @Test fun `cross-exam needs the right statement and the right passage`() {
        val item = overlay("case").levels[0].boss!!.item as CrossExamItem
        val flawed = item.statements.single { it.flawed }
        assertTrue(CrossExamGrader.grade(item, CrossExamAnswer(flawed.id, "c3")).correct)
        assertEquals(setOf(CrossExamGrader.WRONG_EVIDENCE), CrossExamGrader.grade(item, CrossExamAnswer(flawed.id, "c1")).wrongPartIds)
        assertEquals(setOf("st1"), CrossExamGrader.grade(item, CrossExamAnswer("st1", "c1")).wrongPartIds)
    }

    @Test fun `a state saved under one frame does not resume under another`() {
        val st = session(FrameId.CASE).start("L1")
        val restored = SessionCodec.decode(SessionCodec.encode(st))!!
        assertTrue(session(FrameId.CASE).canResume(restored))
        assertFalse(session(FrameId.ESCAPE).canResume(restored))
    }

    @Test fun `every frame plays through on every example package`() {
        dir.listFiles { f -> f.name.startsWith("package.") }!!.forEach { pf ->
            val p = ContractJson.decodePackage(pf.readText())
            FrameId.entries.forEach { id ->
                val o = dir.listFiles { f -> f.name.startsWith("frame.") }!!.map { ContractJson.decodeFrame(it.readText()) }
                    .firstOrNull { it.packageId == p.packageId && it.frameId == id }
                val s = LevelSession(p, GraderRegistry.default(), LeitnerPolicy(), Clock { 0 }, frame = Frames.of(id), overlay = o)
                var st = s.start(p.levels[0].levelId)
                var guard = 0
                while (!st.isFinished && guard++ < 50) {
                    st = if (st.currentSlot!!.kind == SlotKind.SCENE) s.reduce(st, Action.Next).state
                    else s.reduce(s.reduce(st, Action.Submit(right(s, st))).state, Action.Next).state
                }
                assertEquals("${p.packageId}/$id", Phase.Recap, st.phase)
            }
        }
    }
}
