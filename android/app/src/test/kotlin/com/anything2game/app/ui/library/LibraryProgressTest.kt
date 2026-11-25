package com.anything2game.app.ui.library

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.progress.ActivityOutcome
import com.anything2game.contract.progress.CompletionRecord
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.space.LearningPath
import com.anything2game.contract.space.PathNode
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.ports.PackageSummary
import com.anything2game.engine.progress.ActivityProgress
import com.anything2game.engine.progress.NodeStatus
import com.anything2game.engine.progress.ProgressProjection
import com.anything2game.engine.progress.Standing
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.Slot
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LibraryProgressTest {
    private val pkg = ContractJson.decodePackage(File(System.getProperty("contract.examples")!!, "package.v1.example.json").readText())
    private val summary = PackageSummary(pkg.packageId, pkg.title, pkg.language, pkg.levels.size, true)
    private val level = pkg.levels.first()
    private val saved = SessionState(pkg.packageId, pkg.contentVersion, level.levelId, FrameId.CASE, level.units.map { Slot(it.unitId) }, position = 1)
    private val ref = ActivityRef.Practice(pkg.packageId, pkg.contentVersion, level.levelId)
    private val day = 24 * 60 * 60_000L

    private fun ledger(vararg results: Pair<ActivityOutcome, Long>): ActivityProgress =
        ProgressProjection.from("p", results.mapIndexed { i, (outcome, at) -> CompletionRecord.at("c$i", ref.key("p"), outcome, at, "UTC") })
    private fun levelStatus(progress: ActivityProgress, now: Long, open: SessionState? = null) =
        practiceLevels(pkg, open, progress, "p", now).first().status

    @Test fun `wrong answers count as practice but never completed mastery`() {
        val records = level.units.associate { it.unitId to MasteryRecord(it.unitId, attempts = 1, misses = 1, dueAt = 0) }
        val result = libraryEntry(summary, pkg, null, records, 100)
        assertEquals(level.units.size, result.practicedUnits)
        assertEquals(level.units.size, result.dueUnits)
    }
    @Test fun `solid units do not count as due and stale unit records do not inflate stats`() {
        val records = level.units.associate { it.unitId to MasteryRecord(it.unitId, box = 2, attempts = 2) } +
            ("removed" to MasteryRecord("removed", attempts = 1))
        val result = libraryEntry(summary, pkg, null, records, 100)
        assertEquals(level.units.size, result.practicedUnits)
        assertEquals(0, result.dueUnits)
    }
    @Test fun `existing nonclassic sessions resume their own frame while new levels use classic`() {
        val result = libraryEntry(summary, pkg, saved, emptyMap(), 0)
        assertEquals(level.levelId, result.resumableLevelId)
        assertEquals(FrameId.CASE, result.frameFor(level.levelId))
        assertEquals(FrameId.CLASSIC, result.frameFor("another"))
        assertEquals(1, saved.position)
    }
    @Test fun `incompatible version is not offered as resumable and snapshot remains unchanged`() {
        val old = saved.copy(contentVersion = "obsolete")
        val result = libraryEntry(summary, pkg, old, emptyMap(), 0)
        assertNull(result.resumableLevelId)
        assertEquals("obsolete", old.contentVersion)
        assertEquals(1, old.position)
    }

    @Test fun `a level nobody played is new and one that was only answered wrong needs practice`() {
        assertEquals(Standing.NEW, levelStatus(ledger(), 0).standing)
        assertEquals(Standing.NEEDS_PRACTICE, levelStatus(ledger(ActivityOutcome.WRONG to 1_000), 2_000).standing)
    }
    @Test fun `a finished level is done, then due, then learned once it is solid`() {
        val first = ledger(ActivityOutcome.INDEPENDENT to 0)
        assertEquals(Standing.DONE, levelStatus(first, 1_000).standing)
        assertEquals("a day later it is due for another look", Standing.DUE, levelStatus(first, day + 1).standing)
        val second = ledger(ActivityOutcome.INDEPENDENT to 0, ActivityOutcome.INDEPENDENT to day)
        assertEquals(Standing.LEARNED, levelStatus(second, 2 * day).standing)
    }
    @Test fun `a saved session shows its place and wins over the ledger`() {
        val status = levelStatus(ledger(ActivityOutcome.INDEPENDENT to 0), 1_000, saved)
        assertTrue(status.inProgress)
        assertEquals(2, status.position)
        assertEquals(level.units.size, status.total)
        assertNull("another version's session is not this course's", levelStatus(ledger(), 0, saved.copy(contentVersion = "obsolete")).position)
    }

    @Test fun `the practice list and the route say the same about a level at every stage`() {
        val path = LearningPath("route", "1", "space", "Study", "en", listOf(PathNode("level", level.title, ref)))
        val installed = path.dependencies
        val stages = listOf(
            ledger() to 0L,
            ledger(ActivityOutcome.WRONG to 1_000) to 2_000L,
            ledger(ActivityOutcome.INDEPENDENT to 0) to 1_000L,
            ledger(ActivityOutcome.INDEPENDENT to 0) to day + 1,
            ledger(ActivityOutcome.HELPED to 0) to 11 * 60_000L,
            ledger(ActivityOutcome.INDEPENDENT to 0, ActivityOutcome.INDEPENDENT to day) to 2 * day,
        )
        for ((progress, now) in stages) {
            val standing = levelStatus(progress, now).standing
            val route = progress.nodes("p", path, installed, now).getValue("level")
            val expected = when (standing) {
                Standing.NEW, Standing.NEEDS_PRACTICE -> NodeStatus.AVAILABLE
                Standing.DONE, Standing.LEARNED -> NodeStatus.COMPLETE
                Standing.DUE -> NodeStatus.REVIEW
            }
            assertEquals("practice list says $standing, so the route must say $expected", expected, route)
            assertEquals(standing, progress.standings("p", path, now).getValue("level"))
        }
    }

    @Test fun `the mastery rule is the one the projection was given`() {
        val strict = object : com.anything2game.engine.mastery.MasteryPolicy {
            override fun update(record: MasteryRecord, outcome: com.anything2game.engine.mastery.Outcome, now: Long) =
                LeitnerPolicy().update(record, outcome, now).copy(box = 0)
        }
        val records = listOf(CompletionRecord.at("a", ref.key("p"), ActivityOutcome.INDEPENDENT, 0, "UTC"), CompletionRecord.at("b", ref.key("p"), ActivityOutcome.INDEPENDENT, day, "UTC"))
        assertEquals(Standing.LEARNED, practiceLevels(pkg, null, ProgressProjection.from("p", records), "p", 2 * day).first().status.standing)
        assertNotEquals(Standing.LEARNED, practiceLevels(pkg, null, ProgressProjection.from("p", records, strict), "p", 2 * day).first().status.standing)
    }
}
