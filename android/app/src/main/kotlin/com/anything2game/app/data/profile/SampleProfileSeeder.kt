package com.anything2game.app.data.profile

import com.anything2game.app.data.AvatarPreferences
import com.anything2game.app.data.HomePreferences
import com.anything2game.contract.FrameId
import com.anything2game.engine.demo.ScriptedPlayer
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.Grading
import com.anything2game.engine.mastery.MasteryPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.ports.FrameRepository
import com.anything2game.engine.ports.MasteryStore
import com.anything2game.engine.ports.PackageRepository
import com.anything2game.engine.ports.SessionStore
import com.anything2game.engine.session.LevelSession

/**
 * Gives the sample student ("Lin", the persona in why-and-market.md) a believable history by *playing* the preset
 * courses through the real engine at past times, then saving through the same stores a player's taps would use.
 * No hand-written progress: if content or grading changes, the sample history stays valid.
 */
class SampleProfileSeeder(
    private val packages: PackageRepository,
    private val frames: FrameRepository,
    private val grading: Grading,
    private val masteryPolicy: MasteryPolicy,
    private val sessions: SessionStore,
    private val mastery: MasteryStore,
    private val home: HomePreferences,
    private val avatar: AvatarPreferences,
    private val framePrefs: com.anything2game.app.data.FramePreferences,
    private val clock: Clock,
    private val progress: com.anything2game.app.data.learning.LearningProgress? = null,
) {
    /** One past play: which course, which frame, how long ago, which answers were wrong, and whether it was finished. */
    data class Play(val packageId: String, val frame: FrameId, val hoursAgo: Long, val wrong: Set<Int> = emptySet(), val stopAfter: Int? = null)

    val script = listOf(
        Play("pkg_demo_retrieval", FrameId.CLASSIC, hoursAgo = 50, wrong = setOf(2)),
        Play("demo_attention", FrameId.CASE, hoursAgo = 26, wrong = setOf(0)), // one miss → due again today
        Play("demo_mapreduce", FrameId.CASE, hoursAgo = 20),
        Play("demo_resnet", FrameId.CASE, hoursAgo = 2, stopAfter = 2), // left mid-level: "continue" on the home screen
    )

    suspend fun seed(nickname: String, avatarId: String) {
        home.nickname = nickname
        avatar.set(avatarId)
        script.forEach { play ->
            val pkg = packages.load(play.packageId).getOrNull() ?: return@forEach
            val overlay = frames.load(pkg.packageId, play.frame)
            val frame = if (overlay != null || !Frames.of(play.frame).spec.needsOverlay) Frames.of(play.frame) else Frames.CLASSIC
            val past = Clock { clock.now() - play.hoursAgo * 3_600_000L }
            val engine = LevelSession(pkg, grading, masteryPolicy, past, frame = frame, overlay = overlay?.takeIf { frame.id == play.frame })
            val levelId = pkg.levels.first().levelId
            val played = ScriptedPlayer.play(engine, engine.start(levelId).copy(attemptId = "sample-${pkg.packageId}"), { n, _ -> n !in play.wrong }, mastery.all(pkg.packageId, pkg.contentVersion), play.stopAfter)
            played.mastery.values.forEach { mastery.put(pkg.packageId, pkg.contentVersion, it) }
            progress?.practice(played.state, past.now())
            framePrefs.set(pkg.packageId, frame.id) // Practice shows the frame Lin actually plays this course in
            if (!played.state.isFinished) sessions.save(played.state)
        }
    }
}
