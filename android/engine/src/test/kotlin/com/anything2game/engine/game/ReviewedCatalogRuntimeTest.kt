package com.anything2game.engine.game

import com.anything2game.contract.game.*
import org.junit.Assert.*
import org.junit.Test

/** Every reviewed game must be winnable through the real reducer, in both stages, from every seed, with a deterministic trace. */
class ReviewedCatalogRuntimeTest {
    private fun play(spec: GameSpec, seed: Long, undo: Boolean = false): GameRun {
        var run = GameRuntime.start(spec, seed)
        while (!run.completed) {
            when (val c = run.stage.config) {
                is PartitionConfig -> while (run.status == StageStatus.PLAYING) {
                    val b = run.board as PartitionBoard
                    run = GameRuntime.apply(run, GameAction.Split((b.low + b.high) / 2))
                }
                is AllocationConfig -> {
                    val first = (c.firstGoal + c.firstYield - 1) / c.firstYield
                    if (undo) { run = GameRuntime.apply(run, GameAction.Place(Zone.SECOND)); run = GameRuntime.apply(run, GameAction.Undo) }
                    repeat(first) { run = GameRuntime.apply(run, GameAction.Place(Zone.FIRST)) }
                    repeat(c.tokens - first) { run = GameRuntime.apply(run, GameAction.Place(Zone.SECOND)) }
                    run = GameRuntime.apply(run, GameAction.Submit)
                }
            }
            assertEquals(StageStatus.WON, run.status)
            if (!run.completed) run = GameRuntime.apply(run, GameAction.Advance)
        }
        return run
    }

    @Test fun `all reviewed games are solvable in both stages and replay to the identical board`() {
        for (language in listOf("en", "zh")) for (spec in ReviewedGames.all(language)) {
            GameRuntime.validate(spec)
            for (seed in 0L..40L) {
                val run = play(spec, seed)
                val again = GameRuntime.replay(spec, run.checkpoint)
                assertEquals("${spec.gameId} seed $seed", run.board, again.board)
                assertEquals(run.checkpoint, again.checkpoint)
                assertTrue(again.completed)
            }
        }
    }

    @Test fun `a perfect run is three stars, a hint or retry costs a star and nothing wins below one`() {
        for (spec in ReviewedGames.all("en")) {
            val clean = play(spec, 3)
            assertEquals(spec.gameId, 3, GameScore.stars(clean))
            assertEquals(listOf(3, 3), GameScore.stages(spec, clean.checkpoint).map { it.stars })
        }
        val search = ReviewedGames.partition("en")
        var run = GameRuntime.start(search, 15)
        run = GameRuntime.apply(run, GameAction.Help)
        while (run.status == StageStatus.PLAYING) { val b = run.board as PartitionBoard; run = GameRuntime.apply(run, GameAction.Split((b.low + b.high) / 2)) }
        assertEquals(2, GameScore.currentStageStars(run))
        // Lose the stage, retry, still win: a hint, a retry and the wasted questions bottom out at one star.
        var rough = GameRuntime.start(search, 15)
        rough = GameRuntime.apply(rough, GameAction.Help)
        for (split in 1..4) rough = GameRuntime.apply(rough, GameAction.Split(split))
        assertEquals(StageStatus.LOST, rough.status)
        rough = GameRuntime.apply(rough, GameAction.Reset)
        while (rough.status == StageStatus.PLAYING) { val b = rough.board as PartitionBoard; rough = GameRuntime.apply(rough, GameAction.Split((b.low + b.high) / 2)) }
        assertEquals(1, GameScore.currentStageStars(rough))
        assertNull("not yet complete", GameScore.stars(rough))
    }

    @Test fun `changing your mind twice with undo costs one star in an allocation game`() {
        val spec = ReviewedGames.allocation("en")
        var run = GameRuntime.start(spec, 1)
        repeat(2) { run = GameRuntime.apply(run, GameAction.Place(Zone.FIRST)); run = GameRuntime.apply(run, GameAction.Undo) }
        repeat(3) { run = GameRuntime.apply(run, GameAction.Place(Zone.FIRST)) }
        repeat(3) { run = GameRuntime.apply(run, GameAction.Place(Zone.SECOND)) }
        run = GameRuntime.apply(run, GameAction.Submit)
        assertEquals(2, GameScore.currentStageStars(run))
    }

    @Test fun `minimum queries is the ceiling of log2`() {
        assertEquals(listOf(1, 2, 2, 3, 4, 5, 5), listOf(2, 3, 4, 5, 16, 17, 32).map(GameScore::minimumQueries))
    }
}
