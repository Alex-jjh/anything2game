package com.anything2game.app.data

import com.anything2game.app.data.learning.LearningProgress
import com.anything2game.contract.game.ContentVersion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Game stars live in the learning ledger: the best count only goes up, and what older versions kept in preferences is moved in once. */
class LearningStarsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val locker = ContentVersion("locker-search-en", "1")
    private val workshop = ContentVersion("workshop-en", "1")
    private fun ledger(legacy: (ContentVersion) -> Int = { 0 }) = LearningProgress(File(tmp.root, "learning.json"), "p", legacyStars = legacy)

    @Test fun `the best count only goes up and never above three`() = runBlocking {
        val progress = ledger()
        assertEquals(0, progress.bestStars(locker))
        progress.recordStars(locker, 2)
        progress.recordStars(locker, 1)
        assertEquals(2, progress.bestStars(locker))
        progress.recordStars(locker, 9)
        assertEquals(3, progress.bestStars(locker))
        progress.recordStars(locker, 0)
        assertEquals(3, progress.bestStars(locker))
        assertEquals("another game has its own count", 0, progress.bestStars(workshop))
        assertEquals("a reopened ledger remembers", 3, ledger().bestStars(locker))
    }

    @Test fun `stars an older version kept in preferences are moved into the ledger once and then no longer needed`() = runBlocking {
        val first = ledger { if (it == locker) 2 else 0 }
        assertEquals(2, first.bestStars(locker))
        assertEquals(0, first.bestStars(workshop))
        // The preferences are gone (or were never read again): the ledger has the number.
        assertEquals(2, ledger().bestStars(locker))
        // A newer count in the ledger wins over an older preference; an older count is lifted by the preference.
        val second = ledger { 1 }
        assertEquals(2, second.bestStars(locker))
        assertEquals(1, second.bestStars(workshop))
    }

    @Test fun `one read of the ledger answers for the whole shelf`() = runBlocking {
        val progress = ledger { if (it == workshop) 3 else 0 }
        progress.recordStars(locker, 1)
        val states = progress.gameStates(listOf(locker, workshop))
        assertEquals(1, states.getValue(locker).stars)
        assertEquals(3, states.getValue(workshop).stars)
        assertNull(states.getValue(locker).checkpoint)
    }

    @Test fun `a ledger written before stars existed still reads`() = runBlocking {
        File(tmp.root, "learning.json").writeText("""{"checkpoints":[],"events":[]}""")
        assertEquals(0, ledger().bestStars(locker))
        ledger().recordStars(locker, 1)
        assertEquals(1, ledger().bestStars(locker))
    }
}
