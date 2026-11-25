package com.anything2game.app.ui.source

import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.contract.FrameId
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.SourceChunk
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.LevelSession

/** Practice with "Show evidence" already tapped on the first clue that cites the source; the chunk it cites comes with it. */
internal fun practiceEvidence(pkg: LearningPackage): Pair<PlayUiState.Ready, SourceChunk> {
    val engine = LevelSession(pkg, GraderRegistry.default(), LeitnerPolicy(), frame = Frames.of(FrameId.CLASSIC), overlay = null)
    val start = engine.start(pkg.levels[0].levelId)
    val at = start.queue.indices.first { engine.item(start.copy(position = it))?.allSourceRefs?.isNotEmpty() == true }
    val chunkIds = engine.item(start.copy(position = at))!!.allSourceRefs.map { it.chunkId }.distinct()
    val s = engine.reduce(start.copy(position = at), Action.OpenEvidence(chunkIds)).state
    val ready = PlayUiState.Ready(pkg = pkg, frameId = s.frameId, overlay = null, session = s, scene = engine.sceneLines(s), room = engine.room(s), solvedUnitIds = emptySet(),
        unit = engine.unit(s), item = engine.item(s), recap = null,
        context = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL)), retryQueued = false)
    return ready to pkg.sources.first { it.chunkId == chunkIds.first() }
}
