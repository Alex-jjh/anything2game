package com.anything2game.engine.game

import com.anything2game.contract.game.*

/** How one cleared stage went. Derived from the recorded actions, never stored separately, so it can never disagree with the replay. */
data class StageScore(val stageIndex: Int, val stars: Int, val hints: Int, val retries: Int, val undos: Int, val extraQueries: Int)

/**
 * One to three stars per cleared stage: three for a clean run, one fewer for each hint, retry, wasted question and (for every
 * second change of mind) undo, never fewer than one. A game's stars are its stages' average rounded down. A win is always worth
 * at least one star, so stars measure polish and never gate progress.
 */
object GameScore {
    /** The scores of every stage the checkpoint has cleared so far, in order. */
    fun stages(spec: GameSpec, checkpoint: GameCheckpoint): List<StageScore> {
        var run = GameRuntime.start(spec, checkpoint.seed)
        var hints = 0; var retries = 0; var undos = 0; var queries = 0
        val scores = mutableListOf<StageScore>()
        for (action in checkpoint.actions) {
            val before = run
            run = GameRuntime.apply(run, action)
            when (action) {
                GameAction.Help -> hints++
                GameAction.Reset -> { retries++; queries = 0; undos = 0 }
                GameAction.Undo -> undos++
                is GameAction.Split -> queries++
                GameAction.Advance -> { hints = 0; retries = 0; undos = 0; queries = 0 }
                else -> Unit
            }
            if (before.status == StageStatus.PLAYING && run.status == StageStatus.WON) {
                val config = run.stage.config
                val extra = if (config is PartitionConfig) (queries - minimumQueries(config.candidates)).coerceAtLeast(0) else 0
                val penalty = hints + retries + extra + undos / 2
                scores += StageScore(run.stageIndex, (3 - penalty).coerceIn(1, 3), hints, retries, undos, extra)
            }
        }
        return scores
    }

    /** Stars of the whole game once complete, else null. */
    fun stars(run: GameRun): Int? {
        if (!run.completed) return null
        val scores = stages(run.spec, run.checkpoint)
        return (scores.sumOf { it.stars } / scores.size).coerceIn(1, 3)
    }

    /** Stars of the stage the run is currently showing as cleared, else null. */
    fun currentStageStars(run: GameRun): Int? =
        if (run.status == StageStatus.WON) stages(run.spec, run.checkpoint).lastOrNull { it.stageIndex == run.stageIndex }?.stars else null

    /** The most questions a perfect halving can need: ceil(log2 n). */
    fun minimumQueries(candidates: Int): Int {
        var q = 0
        while ((1 shl q) < candidates) q++
        return q
    }
}
