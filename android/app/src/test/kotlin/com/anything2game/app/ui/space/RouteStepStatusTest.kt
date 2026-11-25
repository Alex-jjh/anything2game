package com.anything2game.app.ui.space

import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.awaitIo
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.contract.FrameId
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.space.PathNode
import com.anything2game.engine.progress.Standing
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.Slot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The step sheet asks the view model how the learner stands with a step; a saved session of it says where it was left. */
@RunWith(RobolectricTestRunner::class)
class RouteStepStatusTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun `a practice step with an open session says where it was left, other steps say their standing`() {
        val deps = AndroidAppContainer(app)
        awaitIo { deps.profiles.choose(LocalProfile.GUEST) }
        val pkg = awaitIo { deps.packages.load("demo_attention").getOrThrow() }
        val level = pkg.levels.first()
        val ref = ActivityRef.Practice(pkg.packageId, pkg.contentVersion, level.levelId)
        val step = RouteStep(PathNode("n", level.title, ref), 1, kindOf(ref), StepState.CURRENT, level.title, Standing.NEEDS_PRACTICE, null, null, false)
        val vm = SpaceViewModel.of(deps, "en", null)
        assertEquals("nothing saved: just the standing", Standing.NEEDS_PRACTICE, awaitIo { vm.statusOf(step) }.standing)
        assertFalse(awaitIo { vm.statusOf(step) }.inProgress)
        awaitIo { deps.sessions.save(SessionState(pkg.packageId, pkg.contentVersion, level.levelId, FrameId.CASE, level.units.map { Slot(it.unitId) }, position = 1)) }
        val open = awaitIo { vm.statusOf(step) }
        assertTrue(open.inProgress)
        assertEquals(2, open.position)
        assertEquals(level.units.size, open.total)
        val other = step.copy(node = PathNode("m", "Read", ActivityRef.Tutor("doc", "1", "c")), kind = StepKind.Explain)
        assertFalse("an explanation has no practice session", awaitIo { vm.statusOf(other) }.inProgress)
    }
}
