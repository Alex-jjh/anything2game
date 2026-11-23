package com.anything2game.engine.game

import com.anything2game.contract.game.*
import com.anything2game.contract.progress.ActivityOutcome

enum class StageStatus { PLAYING, WON, LOST }

sealed interface GameBoard
data class PartitionBoard(val low: Int, val high: Int, val queriesLeft: Int, val eliminated: Int = 0) : GameBoard
data class AllocationBoard(val placements: List<Zone>, val available: Int, val firstOutput: Int,
    val secondOutput: Int, val firstOnLeft: Boolean) : GameBoard

class GameRun internal constructor(val spec: GameSpec, val checkpoint: GameCheckpoint, val stageIndex: Int,
    val board: GameBoard, val status: StageStatus, val usedHelp: Boolean) {
    val stage get() = spec.stages[stageIndex]
    val completed get() = stageIndex == spec.stages.lastIndex && status == StageStatus.WON
    val outcome get() = when {
        !completed -> ActivityOutcome.PLAYED
        usedHelp -> ActivityOutcome.HELPED
        else -> ActivityOutcome.INDEPENDENT
    }
}

/** The client reducer and publication verifier share this exact transition function. */
object GameRuntime {
    fun start(spec: GameSpec, seed: Long): GameRun {
        validate(spec)
        return initial(spec, seed)
    }

    fun replay(spec: GameSpec, checkpoint: GameCheckpoint): GameRun {
        val start = start(spec, checkpoint.seed)
        require(checkpoint.gameId == spec.gameId && checkpoint.gameVersion == spec.gameVersion &&
            checkpoint.rulesVersions == start.checkpoint.rulesVersions) { "Checkpoint version mismatch" }
        require(checkpoint.actions.size <= MechanicRegistry.MAX_ACTIONS) { "Action limit exceeded" }
        return checkpoint.actions.fold(start, ::apply)
    }

    fun validate(spec: GameSpec) {
        MechanicRegistry.validate(spec)
        for (stage in spec.stages) {
            // A binary decision tree of depth q can distinguish at most 2^q outcomes, regardless of seed.
            val c = stage.config
            if (c is PartitionConfig) require(c.candidates <= (1 shl c.queryBudget)) { "Queries cannot cover every target" }
        }
        // Exercise actual state transitions, goals and changed conditions across the entire game.
        var run = initial(spec, 0)
        for ((i, stage) in spec.stages.withIndex()) {
            if (i > 0) run = apply(run, GameAction.Advance)
            when (val c = stage.config) {
                is PartitionConfig -> while (run.status == StageStatus.PLAYING) {
                    val b = run.board as PartitionBoard
                    run = apply(run, GameAction.Split((b.low + b.high) / 2))
                }
                is AllocationConfig -> {
                    val first = (c.firstGoal + c.firstYield - 1) / c.firstYield
                    require(first <= c.tokens) { "Unreachable first output" }
                    repeat(first) { run = apply(run, GameAction.Place(Zone.FIRST)) }
                    repeat(c.tokens - first) { run = apply(run, GameAction.Place(Zone.SECOND)) }
                    run = apply(run, GameAction.Submit)
                }
            }
            require(run.status == StageStatus.WON) { "Unsolvable stage ${stage.stageId}" }
        }
        require(run.completed)
    }

    private fun initial(spec: GameSpec, seed: Long): GameRun {
        val checkpoint = GameCheckpoint(spec.gameId, spec.gameVersion, spec.stages.map { it.rulesVersion }, seed, emptyList())
        return newStage(spec, checkpoint, 0, false)
    }

    private fun newStage(spec: GameSpec, checkpoint: GameCheckpoint, index: Int, helped: Boolean): GameRun {
        val board = when (val c = spec.stages[index].config) {
            is PartitionConfig -> PartitionBoard(1, c.candidates, c.queryBudget)
            is AllocationConfig -> AllocationBoard(emptyList(), c.tokens, 0, 0, (checkpoint.seed + index) and 1L == 0L)
        }
        return GameRun(spec, checkpoint, index, board, StageStatus.PLAYING, helped)
    }

    fun apply(run: GameRun, action: GameAction): GameRun {
        require(!run.completed) { "Run already complete" }
        require(run.checkpoint.actions.size < MechanicRegistry.MAX_ACTIONS) { "Run action limit reached" }
        val next = run.checkpoint.copy(actions = run.checkpoint.actions + action)
        if (action == GameAction.Advance) {
            require(run.status == StageStatus.WON) { "Stage goal not met" }
            return newStage(run.spec, next, run.stageIndex + 1, run.usedHelp)
        }
        if (action == GameAction.Reset) {
            require(run.status != StageStatus.WON)
            return newStage(run.spec, next, run.stageIndex, true)
        }
        require(run.status == StageStatus.PLAYING) { "Retry or advance before making another move" }
        if (action == GameAction.Help) return GameRun(run.spec, next, run.stageIndex, run.board, run.status, true)
        val (board, status) = when (val c = run.stage.config) {
            is PartitionConfig -> partition(run, c, action)
            is AllocationConfig -> allocate(run.board as AllocationBoard, c, action)
        }
        return GameRun(run.spec, next, run.stageIndex, board, status, run.usedHelp || status == StageStatus.LOST)
    }

    private fun partition(run: GameRun, c: PartitionConfig, action: GameAction): Pair<GameBoard, StageStatus> {
        val b = run.board as PartitionBoard
        require(action is GameAction.Split && action.after in b.low until b.high) { "Split must leave two nonempty groups" }
        // Fixed arithmetic is stable across app upgrades. The secret is reconstructed, never persisted on the board.
        val target = 1 + Math.floorMod(run.checkpoint.seed + 31L * run.stageIndex, c.candidates.toLong()).toInt()
        val low = if (target > action.after) action.after + 1 else b.low
        val high = if (target <= action.after) action.after else b.high
        val remaining = b.queriesLeft - 1
        val status = when {
            low == high -> StageStatus.WON
            remaining == 0 -> StageStatus.LOST
            else -> StageStatus.PLAYING
        }
        return PartitionBoard(low, high, remaining, (b.high - b.low) - (high - low)) to status
    }

    private fun allocate(b: AllocationBoard, c: AllocationConfig, action: GameAction): Pair<GameBoard, StageStatus> {
        if (action == GameAction.Submit) {
            val won = b.firstOutput >= c.firstGoal && b.secondOutput >= c.secondGoal
            return b to if (won) StageStatus.WON else StageStatus.LOST
        }
        val placements = when (action) {
            is GameAction.Place -> { require(b.available > 0) { "No tokens left" }; b.placements + action.zone }
            GameAction.Undo -> { require(b.placements.isNotEmpty()) { "Nothing to undo" }; b.placements.dropLast(1) }
            else -> error("Action not supported by allocation")
        }
        val first = placements.count { it == Zone.FIRST }
        return AllocationBoard(placements, c.tokens - placements.size, first * c.firstYield,
            (placements.size - first) * c.secondYield, b.firstOnLeft) to StageStatus.PLAYING
    }
}
