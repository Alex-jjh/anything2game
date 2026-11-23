package com.anything2game.contract.pack

import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import org.junit.Assert.*
import org.junit.Test

class ActivityPackTest {
    @Test fun `path install needs every referenced content version`() {
        val ref = ActivityRef.Practice("pdf", "v2", "l1")
        val path = LearningPath("route", "r1", "space", "My route", "en", listOf(PathNode("n1", "Read", ref)))
        val pack = PackCodec.build(path, PackOrigin.SERVER)
        val bytes = PackCodec.toBytes(pack)
        assertTrue(PackCodec.readActivity(bytes.inputStream(), setOf(ContentVersion("pdf", "v1"))) { }.isFailure)
        val ready = PackCodec.readActivity(bytes.inputStream(), setOf(ContentVersion("pdf", "v2"))) { }.getOrThrow()
        assertEquals(path, (ready.payload as ActivityPayload.Path).path)
    }

    @Test fun `game install calls mandatory rules verification and v1 readers reject v2`() {
        val game = ReviewedGames.partition("zh")
        val bytes = PackCodec.toBytes(PackCodec.build(game, PackOrigin.PRESET))
        assertTrue(PackCodec.read(bytes.inputStream()).isFailure)
        assertTrue(PackCodec.readActivity(bytes.inputStream(), emptySet()) { error("unsolvable") }.isFailure)
        assertEquals(game, (PackCodec.readActivity(bytes.inputStream(), emptySet()) { }.getOrThrow().payload as ActivityPayload.Game).game)
        assertTrue(runCatching { PackCodec.build(game.copy(requiredCapabilities = setOf("future")), PackOrigin.SERVER) }.isFailure)
    }
}
