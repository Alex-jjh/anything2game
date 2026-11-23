package com.anything2game.engine.game

import com.anything2game.contract.game.*
import org.junit.Assert.*
import org.junit.Test

class GameRuntimeTest {
    private val search = ReviewedGames.partition("en")
    private val workshop = ReviewedGames.allocation("zh")

    @Test fun `a split eliminates actual candidates and cannot consume another query outside the surviving interval`() {
        val start = GameRuntime.start(search, 42)
        val half = GameRuntime.apply(start, GameAction.Split(8))
        val board = half.board as PartitionBoard
        assertEquals(8, board.high - board.low + 1)
        assertEquals(8, board.eliminated)
        assertEquals(3, board.queriesLeft)
        assertTrue(runCatching { GameRuntime.apply(half, GameAction.Split(8)) }.isFailure)
        assertEquals(half.board, GameRuntime.replay(search, half.checkpoint).board)
        assertTrue(runCatching { GameRuntime.replay(search.copy(gameVersion = "2"), half.checkpoint) }.isFailure)
        assertTrue(runCatching { GameRuntime.replay(search, half.checkpoint.copy(rulesVersions = emptyList())) }.isFailure)
    }

    @Test fun `balanced queries find every target and require a fresh transfer before completion`() {
        for (seed in 0L..31L) {
            var run = GameRuntime.start(search, seed)
            repeat(2) { stage ->
                while (run.status == StageStatus.PLAYING) {
                    val b = run.board as PartitionBoard
                    run = GameRuntime.apply(run, GameAction.Split((b.low + b.high) / 2))
                }
                assertEquals(StageStatus.WON, run.status)
                assertEquals(stage == 1, run.completed)
                if (stage == 0) run = GameRuntime.apply(run, GameAction.Advance)
            }
        }
    }

    @Test fun `resource tokens are finite and submission checks both outputs even after undo`() {
        var run = GameRuntime.start(workshop, 4)
        run = GameRuntime.apply(run, GameAction.Place(Zone.FIRST))
        val one = run.board as AllocationBoard
        assertEquals(2, one.firstOutput)
        assertEquals(5, one.available)
        assertEquals(0, (GameRuntime.apply(run, GameAction.Undo).board as AllocationBoard).firstOutput)
        val failed = GameRuntime.apply(run, GameAction.Submit)
        assertEquals(StageStatus.LOST, failed.status)
        run = GameRuntime.apply(failed, GameAction.Reset)
        repeat(3) { run = GameRuntime.apply(run, GameAction.Place(Zone.FIRST)) }
        repeat(3) { run = GameRuntime.apply(run, GameAction.Place(Zone.SECOND)) }
        assertTrue(runCatching { GameRuntime.apply(run, GameAction.Place(Zone.FIRST)) }.isFailure)
        run = GameRuntime.apply(run, GameAction.Submit)
        assertEquals(StageStatus.WON, run.status)
        assertFalse(run.completed)
        assertTrue(run.usedHelp)
        run = GameRuntime.apply(run, GameAction.Advance)
        assertEquals(4, (run.board as AllocationBoard).available)
    }

    @Test fun `exhausting queries with an unbalanced strategy loses and reset never grants independence`() {
        var run = GameRuntime.start(search, 15)
        for (split in 1..4) run = GameRuntime.apply(run, GameAction.Split(split))
        assertEquals(StageStatus.LOST, run.status)
        assertEquals(12, (run.board as PartitionBoard).let { it.high - it.low + 1 })
        assertTrue(runCatching { GameRuntime.apply(run, GameAction.Advance) }.isFailure)
        val retry = GameRuntime.apply(run, GameAction.Reset)
        assertTrue(retry.usedHelp)
        assertEquals(4, (retry.board as PartitionBoard).queriesLeft)
    }

    @Test fun `pack install runs rules and rejects a partition that only lucky seeds could finish`() {
        val stages = search.stages.map { it.copy(config = (it.config as PartitionConfig).copy(queryBudget = 1)) }
        val pack = com.anything2game.contract.pack.PackCodec
        val bytes = pack.toBytes(pack.build(search.copy(stages = stages), com.anything2game.contract.pack.PackOrigin.SERVER))
        assertTrue(pack.readActivity(bytes.inputStream(), emptySet(), validateGame = GameRuntime::validate).isFailure)
    }

    @Test fun `validation rejects impossible goals unchanged transfer and unsupported rules`() {
        val stage = workshop.stages.first()
        val bad = stage.copy(config = (stage.config as AllocationConfig).copy(firstGoal = 99))
        for (stages in listOf(listOf(bad, workshop.stages.last()), listOf(stage, stage.copy(stageId = "new", transfer = true)),
            listOf(stage.copy(rulesVersion = 9), workshop.stages.last()))) {
            assertTrue(runCatching { GameRuntime.validate(workshop.copy(stages = stages)) }.isFailure)
        }
    }
}
