package com.anything2game.app.ui.library

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.ports.PackageSummary
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

    @Test fun `wrong answers count as practice but never completed mastery`() {
        val records = level.units.associate { it.unitId to MasteryRecord(it.unitId, attempts = 1, misses = 1, dueAt = 0) }
        val result = libraryEntry(summary, pkg, null, records, 100)
        assertEquals(LevelStatus.NeedsReview, result.levels.first().status)
        assertEquals(level.units.size, result.practicedUnits)
        assertEquals(level.units.size, result.dueUnits)
    }
    @Test fun `all solid units are consolidated and stale unit records do not inflate stats`() {
        val records = level.units.associate { it.unitId to MasteryRecord(it.unitId, box = 2, attempts = 2) } +
            ("removed" to MasteryRecord("removed", attempts = 1))
        val result = libraryEntry(summary, pkg, null, records, 100)
        assertEquals(LevelStatus.Done, result.levels.first().status)
        assertEquals(level.units.size, result.practicedUnits)
        assertEquals(0, result.dueUnits)
    }
    @Test fun `existing nonclassic sessions resume their own frame while new levels use classic`() {
        val result = libraryEntry(summary, pkg, saved, emptyMap(), 0)
        assertEquals(LevelStatus.InProgress(2, level.units.size), result.levels.first().status)
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
}
