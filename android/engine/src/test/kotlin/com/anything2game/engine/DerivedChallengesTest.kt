package com.anything2game.engine

import com.anything2game.contract.ContractJson
import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.Item
import com.anything2game.contract.KnowledgeType
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.OrderItem
import com.anything2game.contract.OrderStep
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.SortBin
import com.anything2game.contract.SortCard
import com.anything2game.contract.SortItem
import com.anything2game.contract.SourceRef
import com.anything2game.contract.Statement
import com.anything2game.contract.SwipeCard
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.demo.ScriptedPlayer
import com.anything2game.engine.frames.Claim
import com.anything2game.engine.frames.DerivedChallenges
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.Effect
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SessionCodec
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.Slot
import com.anything2game.engine.session.SlotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Everything here runs on the real example and demo packages: derived challenges must hold up on real generated content. */
class DerivedChallengesTest {
    private val dirs = listOf(System.getProperty("contract.examples")!!, System.getProperty("contract.samples")!!).map(::File)
    private val packages: List<LearningPackage> = dirs.flatMap { d -> d.listFiles { f -> f.name.startsWith("package.") }!!.sortedBy { it.name } }
        .map { ContractJson.decodePackage(it.readText()) }
    private val overlays: List<FrameOverlay> = dirs.flatMap { d -> d.listFiles { f -> f.name.startsWith("frame.") }!!.sortedBy { it.name } }
        .map { ContractJson.decodeFrame(it.readText()) }
    private fun overlay(p: LearningPackage, id: FrameId) = overlays.firstOrNull { it.packageId == p.packageId && it.frameId == id }
    private fun session(p: LearningPackage, id: FrameId) =
        LevelSession(p, GraderRegistry.default(), LeitnerPolicy(), Clock { 0 }, frame = Frames.of(id), overlay = overlay(p, id))
    private val allUnits get() = packages.flatMap { p -> p.levels.flatMap { it.units } }

    // ---- hand-made items (small and explicit) -------------------------------------------------

    private val ref = listOf(SourceRef("c1", "q"))
    private val sort = SortItem("p", listOf(SortBin("a", "Alpha"), SortBin("b", "Beta")),
        listOf(SortCard("k1", "One.", "a"), SortCard("k2", "Two", "b"), SortCard("k3", "Three", "a")), "because", ref)
    private val order = OrderItem("p", listOf(OrderStep("s1", "First."), OrderStep("s2", "Second"), OrderStep("s3", "Third")), "in this order", ref)

    @Test fun `sort claims - the wrong one files a card under another bin, the true one under its own, about different cards`() {
        val wrong = DerivedChallenges.misconceptionOf(sort, "en", "U")!!
        val right = DerivedChallenges.truthOf(sort, "en", "U")!!
        assertFalse(wrong.holds); assertTrue(right.holds)
        val (wc, wb) = Regex("“(.*)” belongs under “(.*)”").matchEntire(wrong.text)!!.destructured
        val (rc, rb) = Regex("“(.*)” belongs under “(.*)”").matchEntire(right.text)!!.destructured
        fun bin(card: String) = sort.cards.first { it.text.trimEnd('.') == card }.binId
        assertTrue(sort.bins.first { it.label == wb }.id != bin(wc))
        assertEquals(sort.bins.first { it.label == rb }.id, bin(rc))
        assertTrue(wc != rc)
        assertEquals("because", wrong.why); assertEquals(ref, wrong.sourceRefs)
    }

    @Test fun `order claims - the wrong one swaps a pair, the true one keeps the real order`() {
        val steps = order.steps.map { it.text.trimEnd('.') }
        repeat(6) { n ->
            val wrong = DerivedChallenges.misconceptionOf(order, "en", "U$n")!!
            val right = DerivedChallenges.truthOf(order, "en", "U$n")!!
            fun parse(c: Claim) = Regex("“(.*)” comes before “(.*)”").matchEntire(c.text)!!.destructured.let { (a, b) -> steps.indexOf(a) to steps.indexOf(b) }
            assertTrue(parse(wrong).let { it.first > it.second })
            assertTrue(parse(right).let { it.first < it.second })
        }
    }

    @Test fun `chinese templates use the corner quotes`() {
        assertTrue(DerivedChallenges.misconceptionOf(sort, "zh", "U")!!.text.contains("」应归入「"))
        assertTrue(DerivedChallenges.misconceptionOf(order, "zh", "U")!!.text.contains("」排在「"))
    }

    @Test fun `cross-exam gives the flawed statement and its why`() {
        val item = CrossExamItem("rookie", "p", listOf(
            Statement("a", "Fine.", false, "ok", emptyList()), Statement("b", "Bad.", true, "overclaim", ref), Statement("c", "Fine too.", false, "ok", emptyList()),
        ))
        assertEquals(Claim("Bad.", false, "overclaim", ref), DerivedChallenges.misconceptionOf(item))
        assertNull("a true statement without a citation cannot be derived", DerivedChallenges.truthOf(item))
    }

    // ---- real content ----------------------------------------------------------------------------

    @Test fun `every kind of item in the examples and demos yields claims that carry their sources`() {
        val seen = mutableSetOf<String>()
        val bosses = overlays.mapNotNull { it.levels.firstNotNullOfOrNull { l -> l.boss }?.item }
        (allUnits.map { it.item } + allUnits.flatMap { it.variants } + bosses).forEach { item ->
            seen += item::class.simpleName!!
            listOf(DerivedChallenges.misconceptionOf(item, "en", "x"), DerivedChallenges.truthOf(item, "en", "x")).filterNotNull().forEach { c ->
                assertTrue(c.text.isNotBlank() && c.why.isNotBlank() && c.sourceRefs.isNotEmpty())
                assertTrue("claim cites only the item's own passages", c.sourceRefs.all { it in item.allSourceRefs })
            }
            if (item is SwipeItem && item.cards.any { !it.supported }) assertFalse(DerivedChallenges.misconceptionOf(item)!!.holds)
            if (item !is CrossExamItem) assertNotNull(item::class.simpleName, DerivedChallenges.misconceptionOf(item))
        }
        assertEquals(setOf("SwipeItem", "SortItem", "DeduceItem", "OrderItem", "CrossExamItem"), seen)
    }

    @Test fun `derived items validate like any item and stay inside the package's passages`() {
        packages.forEach { p ->
            val chunks = p.sources.associateBy { it.chunkId }
            val lang = p.language
            p.levels.forEach { level ->
                val derived = level.units.mapNotNull { u -> DerivedChallenges.teachBack(u, level.units.filter { it != u }, "Sam", lang) } +
                    listOfNotNull(DerivedChallenges.roomCheck(level.units, lang))
                derived.forEach { item ->
                    val problems = mutableListOf<String>()
                    PackageValidator.checkUnit("${p.packageId}/${level.levelId}", LearningUnit("D", KnowledgeType.CLAIM, item.mechanic, item = item), chunks) { path, m -> problems += "$path $m" }
                    assertEquals(problems.toString(), emptyList<String>(), problems)
                    when (item) {
                        is DeduceItem -> {
                            assertEquals(1, item.options.count { it.correct })
                            assertTrue(item.options.size in 2..3)
                            assertTrue(item.options.all { it.feedback.isNotBlank() })
                            assertEquals(item.options.size, item.options.map { it.text }.distinct().size)
                            assertTrue(item.sourceRefs.isNotEmpty())
                        }
                        is SwipeItem -> {
                            assertTrue(item.cards.size in 2..3)
                            assertTrue(item.cards.all { it.why.isNotBlank() && it.sourceRefs.isNotEmpty() })
                            assertEquals(item.cards.size, item.cards.map { it.statement }.distinct().size)
                        }
                        else -> error("unexpected $item")
                    }
                }
            }
        }
    }

    @Test fun `teach-back - the correct explanation is the claim's own why, the others come from other units`() {
        val p = packages.first { it.packageId.contains("attention") }
        val level = p.levels[0]
        val unit = level.units[0]
        val claim = DerivedChallenges.misconceptionOf(unit.item, p.language, unit.unitId)!!
        val tb = DerivedChallenges.teachBack(unit, level.units.drop(1), "Sam", "en")!!
        assertEquals("Sam: “${claim.text.trimEnd('.')}”", tb.scenario)
        assertEquals("Which explanation would you give Sam?", tb.prompt)
        assertEquals(claim.why, tb.options.single { it.correct }.text)
        val others = level.units.drop(1).mapNotNull { DerivedChallenges.misconceptionOf(it.item, "en", it.unitId)?.why }.toSet()
        assertTrue(tb.options.filter { !it.correct }.all { it.text in others })
        assertTrue(DerivedChallenges.teachBack(unit, emptyList(), "Sam", "en") == null)
    }

    @Test fun `a rookie quoting a claim that already opens with a quote does not double the quotes`() {
        val tb = DerivedChallenges.teachBack(LearningUnit("U", KnowledgeType.PROCESS, order.mechanic, item = order), listOf(LearningUnit("V", KnowledgeType.CLAIM, sort.mechanic, item = sort)), "Sam", "en")!!
        assertTrue(tb.scenario, Regex("Sam: “[^“”]*” comes before “[^“”]*”").matches(tb.scenario))
    }

    @Test fun `derivation is deterministic`() {
        packages.forEach { p ->
            FrameId.entries.forEach { id ->
                val a = session(p, id); val b = session(p, id)
                p.levels.forEach { level ->
                    val sa = a.start(level.levelId); val sb = b.start(level.levelId)
                    assertEquals(sa.queue, sb.queue)
                    sa.queue.indices.forEach { i ->
                        assertEquals("${p.packageId}/$id/$i", a.item(sa.copy(position = i)), b.item(sb.copy(position = i)))
                    }
                }
            }
        }
        assertEquals(DerivedChallenges.stableHash("L1U1"), DerivedChallenges.stableHash("L1U1"))
        assertEquals("FNV-1a test vector for \"a\" (sign bit cleared): the hash is pinned, not JVM-dependent", 0x640c292c, DerivedChallenges.stableHash("a"))
        assertTrue(DerivedChallenges.stableHash("anything") >= 0)
    }

    // ---- plans -----------------------------------------------------------------------------------

    @Test fun `rookie plans at most two teach-backs per level, each right after its own unit`() {
        var total = 0
        packages.forEach { p ->
            if (overlay(p, FrameId.ROOKIE) == null) return@forEach
            p.levels.forEach { level ->
                val q = session(p, FrameId.ROOKIE).start(level.levelId).queue
                val tbs = q.withIndex().filter { it.value.kind == SlotKind.TEACH_BACK }
                assertTrue("${p.packageId}/${level.levelId}", tbs.size <= 2)
                tbs.forEach { (i, slot) ->
                    assertEquals(SlotKind.UNIT, q[i - 1].kind); assertEquals(slot.unitId, q[i - 1].unitId)
                }
                // the earliest eligible units, in order
                assertEquals(level.units.map { it.unitId }.filter { it in tbs.map { t -> t.value.unitId } }, tbs.map { it.value.unitId })
                total += tbs.size
            }
        }
        assertTrue("some level has a teach-back", total > 0)
    }

    @Test fun `only rookie plans teach-backs and only escape plans room locks`() {
        packages.forEach { p ->
            FrameId.entries.forEach { id ->
                if (id != FrameId.CLASSIC && overlay(p, id) == null) return@forEach
                p.levels.forEach { level ->
                    val kinds = session(p, id).start(level.levelId).queue.map { it.kind }.toSet()
                    if (id != FrameId.ROOKIE) assertFalse("$id", SlotKind.TEACH_BACK in kinds)
                    if (id != FrameId.ESCAPE) assertFalse("$id", SlotKind.ROOM_CHECK in kinds)
                }
            }
        }
    }

    @Test fun `escape closes each eligible room with exactly one lock, and skips rooms that yield fewer than two claims`() {
        var locks = 0
        var skipped = 0
        packages.forEach { p ->
            val o = overlay(p, FrameId.ESCAPE) ?: return@forEach
            val s = session(p, FrameId.ESCAPE)
            p.levels.forEach { level ->
                val q = s.start(level.levelId).queue
                o.level(level.levelId)!!.rooms.forEach { room ->
                    val units = room.unitIds.map { id -> level.units.first { it.unitId == id } }
                    val lock = q.withIndex().filter { it.value.kind == SlotKind.ROOM_CHECK && it.value.roomId == room.id }
                    if (DerivedChallenges.roomCheck(units, p.language) == null) { assertTrue(lock.isEmpty()); skipped++ }
                    else {
                        assertEquals("${p.packageId}/${level.levelId}/${room.id}", 1, lock.size); locks++
                        val last = q.indexOfLast { it.kind == SlotKind.UNIT && it.unitId == room.unitIds.last() }
                        assertEquals("the lock follows the room's last unit", last + 1, lock.single().index)
                        val item = s.item(s.start(level.levelId).copy(position = lock.single().index)) as SwipeItem
                        assertTrue(item.cards.size in 2..3)
                        val own = units.flatMap { u -> u.item.allSourceRefs }.toSet()
                        assertTrue("a lock cites only its room's passages", item.allSourceRefs.all { it in own })
                    }
                }
            }
        }
        assertTrue("locks=$locks", locks > 0)
    }

    @Test fun `a lock mixes claims that hold with claims that do not`() {
        packages.forEach { p ->
            p.levels.forEach { level ->
                level.units.windowed(2).forEach { pair ->
                    DerivedChallenges.roomCheck(pair, p.language)?.let { lock ->
                        assertTrue(lock.cards.any { it.supported } && lock.cards.any { !it.supported })
                    }
                }
            }
        }
    }

    // ---- play ------------------------------------------------------------------------------------

    @Test fun `scripted players finish every level of every package in every frame`() {
        var derivedAnswered = 0
        packages.forEach { p ->
            FrameId.entries.forEach { id ->
                if (id != FrameId.CLASSIC && overlay(p, id) == null) return@forEach
                val s = session(p, id)
                p.levels.forEach { level ->
                    val played = ScriptedPlayer.play(s, s.start(level.levelId), { _, _ -> true })
                    assertEquals("${p.packageId}/$id/${level.levelId}", Phase.Recap, played.state.phase)
                    derivedAnswered += played.state.results.count { it.slot.derived() }
                    // with every second answer wrong the level still ends (in the recap, or cold when the frame has stakes)
                    val rough = ScriptedPlayer.play(s, s.start(level.levelId), { n, _ -> n % 2 == 0 })
                    assertTrue("${p.packageId}/$id/${level.levelId}", rough.state.isFinished)
                }
            }
        }
        assertTrue(derivedAnswered > 0)
    }

    private fun Slot.derived() = kind == SlotKind.TEACH_BACK || kind == SlotKind.ROOM_CHECK

    /** Plays [levelId] answering units right and derived slots as [derivedRight]; returns the final state and every effect emitted at a derived slot. */
    private fun play(p: LearningPackage, id: FrameId, levelId: String, derivedRight: Boolean): Triple<SessionState, List<Effect>, LevelSession> {
        val s = session(p, id)
        var st = s.start(levelId)
        val onDerived = mutableListOf<Effect>()
        var guard = 0
        while (!st.isFinished && guard++ < 200) {
            val item = s.item(st)
            st = if (item == null) s.reduce(st, Action.Next).state else {
                val right = derivedRight || st.currentSlot!!.kind !in setOf(SlotKind.TEACH_BACK, SlotKind.ROOM_CHECK)
                val t = s.reduce(st, Action.Submit(if (right) ScriptedPlayer.correctAnswer(item) else ScriptedPlayer.wrongAnswer(item)))
                if (st.currentSlot!!.kind in setOf(SlotKind.TEACH_BACK, SlotKind.ROOM_CHECK)) onDerived += t.effects
                s.reduce(t.state, Action.Next).state
            }
        }
        return Triple(st, onDerived, s)
    }

    @Test fun `derived slots never touch mastery or the unit recap, whatever the player answers`() {
        var checked = 0
        listOf(FrameId.ROOKIE, FrameId.ESCAPE).forEach { id ->
            packages.forEach { p ->
                if (overlay(p, id) == null) return@forEach
                p.levels.forEach { level ->
                    val good = play(p, id, level.levelId, derivedRight = true)
                    val bad = play(p, id, level.levelId, derivedRight = false)
                    if (good.first.queue.none { it.derived() }) return@forEach
                    checked++
                    assertEquals(Phase.Recap, good.first.phase)
                    if (bad.first.phase != Phase.Recap) return@forEach // out of credibility: the recap is not reached
                    assertEquals(emptyList<Effect>(), good.second)
                    assertEquals(emptyList<Effect>(), bad.second)
                    assertEquals(good.third.recap(good.first).solidUnitIds, bad.third.recap(bad.first).solidUnitIds)
                    assertEquals(good.third.recap(good.first).shakyUnitIds, bad.third.recap(bad.first).shakyUnitIds)
                    assertEquals(good.third.recap(good.first).firstTryRate, bad.third.recap(bad.first).firstTryRate, 0.0)
                    assertEquals("a wrong derived slot is not queued again", good.first.queue.size, bad.first.queue.size)
                    assertEquals(good.third.solvedUnitIds(good.first), bad.third.solvedUnitIds(bad.first))
                }
            }
        }
        assertTrue(checked > 0)
    }

    @Test fun `a wrong lock costs credibility like any escape challenge, and rookie has none to lose`() {
        val p = packages.first { it.packageId.contains("attention") }
        val s = session(p, FrameId.ESCAPE)
        var st = s.start("L1")
        st = st.copy(position = st.queue.indexOfFirst { it.kind == SlotKind.ROOM_CHECK })
        val item = s.item(st)!!
        assertEquals(3, st.credibility)
        val wrong = s.reduce(st, Action.Submit(ScriptedPlayer.wrongAnswer(item))).state
        assertEquals(2, wrong.credibility)
        assertEquals(st.queue, wrong.queue)
        assertEquals(3, s.reduce(st, Action.Submit(ScriptedPlayer.correctAnswer(item))).state.credibility)

        val r = session(p, FrameId.ROOKIE)
        val rs = r.start("L1").let { it.copy(position = it.queue.indexOfFirst { s2 -> s2.kind == SlotKind.TEACH_BACK }) }
        assertNull(rs.credibility)
        assertNull(r.unit(rs))
        // no hint to reveal on a derived slot
        assertEquals(rs, r.reduce(rs, Action.RevealHint).state)
        assertEquals("Sam", r.rookieName())
    }

    // ---- saved sessions --------------------------------------------------------------------------

    @Test fun `old saved sessions still decode, new ones round-trip`() {
        val old = """{"packageId":"pkg","contentVersion":"1","levelId":"L1","frameId":"escape","queue":[{"unitId":"","kind":"SCENE","scene":"intro"},{"unitId":"L1U1"},{"unitId":"L1U1","variant":0,"retry":true},{"unitId":"B","kind":"BOSS"}],"position":1,"credibility":3}"""
        val st = SessionCodec.decode(old)
        assertNotNull(st)
        assertEquals(listOf(SlotKind.SCENE, SlotKind.UNIT, SlotKind.UNIT, SlotKind.BOSS), st!!.queue.map { it.kind })
        assertNull(st.queue[1].roomId)

        val p = packages.first { it.packageId.contains("attention") }
        listOf(FrameId.ROOKIE, FrameId.ESCAPE).forEach { id ->
            val fresh = session(p, id).start("L1")
            assertTrue(fresh.queue.any { it.derived() })
            val back = SessionCodec.decode(SessionCodec.encode(fresh))
            assertEquals(fresh, back)
            assertTrue(session(p, id).canResume(back!!))
            // a derived slot that no longer resolves (room removed from the overlay) makes the save unusable instead of crashing
            val broken = fresh.copy(queue = fresh.queue + Slot("", kind = SlotKind.ROOM_CHECK, roomId = "nope"))
            assertFalse(session(p, id).canResume(broken))
        }
    }
}
