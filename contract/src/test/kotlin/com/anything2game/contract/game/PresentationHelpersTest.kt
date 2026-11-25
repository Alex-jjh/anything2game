package com.anything2game.contract.game

import com.anything2game.contract.SourceChunk
import org.junit.Assert.*
import org.junit.Test

/** The small helpers every screen shares, so the same thing is never worded twice. */
class PresentationHelpersTest {
    @Test fun `a page is named by its printed label, else counted from one`() {
        assertEquals("iv", SourceChunk("c", 3, "iv", "t").displayPage())
        assertEquals("4", SourceChunk("c", 3, null, "t").displayPage())
        assertEquals("1", SourceChunk("c", 0, null, "t").displayPage())
        assertEquals("iv", EvidenceRef.Pdf("e", "d", "1", "c", "q", 3, "iv").displayPage())
        assertEquals("4", EvidenceRef.Pdf("e", "d", "1", "c", "q", 3, null).displayPage())
    }

    @Test fun `every reviewed game finds its own catalogue entry and nothing else does`() {
        for (language in listOf("en", "zh")) for (entry in ReviewedGames.catalog) {
            assertSame(entry, ReviewedGames.entryFor(entry.spec(language)))
        }
        val generated = ReviewedGames.all("en").first().copy(gameId = "game_8c1f")
        assertNull(ReviewedGames.entryFor(generated))
        // "locker-search-notes" is not "locker-search": ids are key-language, not a prefix.
        assertNull(ReviewedGames.entryFor(ReviewedGames.all("en").first().copy(gameId = "locker-search-notes")))
    }

    @Test fun `a stage's icons fall back to the neutral ones in one place`() {
        val bare = PartitionConfig(8, 3)
        assertEquals(GameIcons.DEFAULT_PARTITION, bare.icon())
        assertEquals(GameIcons.DEFAULT_PARTITION, bare.mainIcon())
        assertEquals("page", PartitionConfig(8, 3, PartitionTheme(icon = "page")).icon())
        val plain = AllocationConfig(6, 2, 3, 6, 6)
        assertEquals(AllocationIcons("bowl", "cup", "coin"), plain.icons())
        assertEquals("bowl", plain.mainIcon())
        assertEquals(AllocationIcons("wine", "cloth", "coin"), AllocationConfig(6, 2, 3, 6, 6, AllocationTheme(firstIcon = "wine", secondIcon = "cloth")).icons())
        assertEquals("wine", AllocationConfig(6, 2, 3, 6, 6, AllocationTheme(firstIcon = "wine")).mainIcon())
        for (game in ReviewedGames.all("en")) game.stages.forEach { stage ->
            val config = stage.config
            val all = when (config) { is PartitionConfig -> listOf(config.icon()); is AllocationConfig -> config.icons().let { listOf(it.first, it.second, it.token) } }
            all.forEach { assertTrue("$it is drawable", it in GameIcons.partition || it in GameIcons.allocation) }
        }
    }
}
