package com.anything2game.app.data

import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.profile.LocalProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Guest and sample student are two local profiles behind the same store interfaces; switching never mixes or deletes data. */
@RunWith(RobolectricTestRunner::class)
class ProfilesTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun `a fresh install asks who is playing`() {
        assertNull(AndroidAppContainer(app).profiles.active.value)
    }

    @Test fun `the sample student arrives with a history made by the real engine`() = runBlocking {
        val deps = AndroidAppContainer(app)
        deps.profiles.choose(LocalProfile.SAMPLE)
        assertEquals("Lin", deps.homePreferences.nickname)
        assertEquals("pip", deps.avatarPreferences.get())
        val attention = deps.mastery.all("demo_attention").values
        assertTrue("attention was played", attention.isNotEmpty())
        assertTrue("one clue was missed and is due again", attention.any { it.misses > 0 && it.dueAt <= deps.clock.now() })
        val open = deps.sessions.load("demo_resnet")
        assertNotNull("ResNet case left half-way", open)
        assertTrue(!open!!.isFinished && open.results.isNotEmpty())
    }

    @Test fun `profiles keep separate progress and switching back does not re-seed`() = runBlocking {
        val deps = AndroidAppContainer(app)
        deps.profiles.choose(LocalProfile.SAMPLE)
        val before = deps.mastery.all("demo_attention")
        deps.profiles.choose(LocalProfile.GUEST)
        assertTrue(deps.mastery.all("demo_attention").isEmpty())
        assertNull(deps.sessions.load("demo_resnet"))
        assertEquals("", deps.homePreferences.nickname)
        deps.profiles.choose(LocalProfile.SAMPLE)
        assertEquals(before, deps.mastery.all("demo_attention"))
        // a new container (app restart) remembers the choice
        assertEquals(LocalProfile.SAMPLE, AndroidAppContainer(app).profiles.active.value)
    }

    @Test fun `an install with data from before profiles continues as the guest`() {
        File(app.filesDir, "sessions").apply { mkdirs() }.resolve("x.json").writeText("{}")
        assertEquals(LocalProfile.GUEST, AndroidAppContainer(app).profiles.active.value)
    }
}
