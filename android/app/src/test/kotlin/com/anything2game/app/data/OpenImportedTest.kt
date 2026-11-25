package com.anything2game.app.data

import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.awaitIo
import com.anything2game.app.data.learning.OpenImported
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.platform.ContextSimulatorControls
import com.anything2game.engine.context.ContextSignals
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Where a finished import lands, and the hand-driven context controls: both asked of the container, not worked out by a screen. */
@RunWith(RobolectricTestRunner::class)
class OpenImportedTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun `a course finds the Space that holds it and its lesson`() {
        val deps = AndroidAppContainer(app)
        awaitIo { deps.profiles.choose(LocalProfile.GUEST) }
        val destination = awaitIo { OpenImported(deps.materials, deps.spaces)("demo_attention") }!!
        val material = awaitIo { deps.materials.materials().single { it.localPack?.packageId == "demo_attention" } }
        val space = awaitIo { deps.spaces.list().first { material.member in it.members } }
        assertEquals(space.spaceId, destination.spaceId)
        assertEquals("demo_attention", destination.sourcePackage)
        assertEquals(material.tutorKey, destination.sessionKey)
        assertEquals(material.localPack!!.contentVersion, destination.version)
        // The lesson's own key finds the same place.
        assertEquals(destination, awaitIo { OpenImported(deps.materials, deps.spaces)(material.tutorKey!!) })
    }

    @Test fun `something no Space holds yet leads nowhere in particular`() {
        val deps = AndroidAppContainer(app)
        awaitIo { deps.profiles.choose(LocalProfile.GUEST) }
        assertNull(awaitIo { OpenImported(deps.materials, deps.spaces)("no-such-lesson") })
    }

    @Test fun `the demo controls are the very source the levels listen to, and only a simulated source has them`() {
        val deps = AndroidAppContainer(app)
        val controls: ContextSimulatorControls = deps.contextSimulator!!
        assertSame(deps.contextSource, controls)
        controls.setActivity(ContextSignals.Activity.WALKING)
        assertEquals(ContextSignals.Activity.WALKING, deps.contextSource.signals.value.activity)
        val real = object : com.anything2game.app.AppDependencies by deps { override val contextSimulator: ContextSimulatorControls? = null }
        assertNull("a sensor-backed container offers no controls", real.contextSimulator)
    }
}
