package com.anything2game.app.data

import com.anything2game.app.data.content.*
import com.anything2game.app.data.learning.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LearningDataTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `game installs retain both versions and reject replacement under an immutable id`() = runBlocking {
        val disk = DirectoryPackSource(tmp.newFolder())
        val library = ContentLibrary(listOf(disk))
        val one = ReviewedGames.allocation("zh")
        val two = one.copy(gameVersion = "2")
        for (spec in listOf(one, two)) {
            library.install(PackCodec.toBytes(PackCodec.build(spec, PackOrigin.PRESET)).inputStream())
        }
        val collision = one.copy(title = "Different")
        assertTrue(runCatching { library.install(PackCodec.toBytes(PackCodec.build(collision, PackOrigin.PRESET)).inputStream()) }.isFailure)
        assertEquals(one, library.game(ContentVersion(one.gameId, one.gameVersion)))
        assertEquals(two, library.game(ContentVersion(two.gameId, two.gameVersion)))
    }

    @Test fun `replayed final action survives restart without a second promotion`() = runBlocking {
        val file = File(tmp.newFolder(), "progress.json")
        fun reopen() = LearningProgress(file, "guest")
        val spec = ReviewedGames.allocation("en")
        val repo = reopen()
        var run = repo.start(spec, 17L)
        val actions = List(3) { GameAction.Place(Zone.FIRST) } + List(3) { GameAction.Place(Zone.SECOND) } +
            listOf(GameAction.Submit, GameAction.Advance) + List(3) { GameAction.Place(Zone.FIRST) } + GameAction.Place(Zone.SECOND)
        for (action in actions) run = repo.apply(spec, run.checkpoint, action)
        val finished = repo.apply(spec, run.checkpoint, GameAction.Submit)
        assertTrue(finished.completed)
        assertEquals(finished.checkpoint, reopen().apply(spec, run.checkpoint, GameAction.Submit).checkpoint)
        assertEquals(1, reopen().projection().mastery.values.single().attempts)
        assertEquals(1, reopen().projection().days.size)
    }

    @Test fun `legacy mid-level checkpoint remains accessible after saving a later version`() = runBlocking {
        val dir = tmp.newFolder()
        val saved = com.anything2game.engine.session.SessionState("pack", "版本:1", "第1关", queue = listOf(com.anything2game.engine.session.Slot("first")))
        File(dir, "pack.json").writeText(com.anything2game.engine.session.SessionCodec.encode(saved))
        val next = saved.copy(contentVersion = "版本/1", levelId = "第2关")
        FileSessionStore(dir).save(next)
        assertEquals(saved, FileSessionStore(dir).load("pack", "版本:1"))
        assertEquals(next, FileSessionStore(dir).load("pack", "版本/1"))
    }

    @Test fun `migration groups the source once and respects removed relationships across restart`() = runBlocking {
        val file = File(tmp.newFolder(), "spaces.json")
        val local = SpaceMember.LocalPackRef("旧教材", "版本:1")
        val material = Material(ContentVersion("document", "sha256:1"), "Old course", local, emptyList())
        fun reopen() = SpaceRepository(file, { listOf(material) })
        val original = reopen().list().single()
        assertEquals("Old course", original.title)
        reopen().remove(original.spaceId, local)
        assertTrue(reopen().list().single().members.isEmpty())
        assertEquals(original.spaceId, reopen().list().single().spaceId)
    }
}
