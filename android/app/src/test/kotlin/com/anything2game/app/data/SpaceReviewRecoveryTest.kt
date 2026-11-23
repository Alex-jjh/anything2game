package com.anything2game.app.data

import android.net.Uri
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.learning.*
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import com.anything2game.engine.progress.NodeStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SpaceReviewRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun all_confirmed_siblings_survive_cancellation_while_staging_the_first_file() = runBlocking {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        val dir = files.newFolder()
        val staging = CompletableDeferred<Unit>()
        val queue = PdfImportQueue(dir, { staging.complete(Unit); awaitCancellation() }, deps.tutors, deps.spaces)
        val selected = listOf("a.pdf", "b.pdf", "c.pdf").map { ImportRequest.PdfFile(it, Uri.parse("content://docs/$it"), 12) }
        val task = launch { queue.enqueue("group", selected, "restored-batch") }
        staging.await()
        task.cancelAndJoin()
        val reopened = PdfImportQueue(dir, { "%PDF-saved".toByteArray() }, deps.tutors, deps.spaces)
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf"), reopened.list().map { it.title })
        val ids = reopened.list().map { it.id }
        val resumed = reopened.enqueue("group", selected, "restored-batch")
        assertEquals(ids, resumed.map { it.id })
        assertEquals(3, reopened.list().size)
        assertTrue(resumed.all { it.staged && it.sessionId == null && it.spaceId == "group" })
    }

    @Test fun game_paths_accept_only_the_creditable_transfer_and_unlock_its_dependent_after_the_real_win() = runBlocking {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        val game = ReviewedGames.allocation("en")
        val first = ActivityRef.Game(game.gameId, game.gameVersion, "practice")
        val transfer = first.copy(activityId = "transfer")
        val follow = deps.materials.materials().flatMap { it.nodes }.first { it.activity is ActivityRef.Practice }
        fun path(ref: ActivityRef.Game) = LearningPath("trail", "1", "space", "Plan", "en",
            listOf(PathNode("workshops", game.title, ref), follow.copy(nodeId = "after", prerequisites = listOf("workshops"))))
        assertTrue(runCatching { deps.materials.verify(path(first)) }.isFailure)
        assertNull(deps.materials.resolve(first))
        val valid = path(transfer)
        deps.materials.verify(valid)
        fun status() = runBlocking { deps.materials.nodeStatuses(valid, deps.learningProgress, deps.clock.now()) }
        assertEquals(NodeStatus.LOCKED, status()["after"])
        var run = deps.learningProgress.start(game, 12L)
        val moves = List(3) { GameAction.Place(Zone.FIRST) } + List(3) { GameAction.Place(Zone.SECOND) } +
            listOf(GameAction.Submit, GameAction.Advance) + List(3) { GameAction.Place(Zone.FIRST) } +
            listOf(GameAction.Place(Zone.SECOND), GameAction.Submit)
        moves.forEach { run = deps.learningProgress.apply(game, run.checkpoint, it) }
        assertTrue(run.completed)
        assertEquals(NodeStatus.COMPLETE, status()["workshops"])
        assertEquals(NodeStatus.AVAILABLE, status()["after"])
    }
}
