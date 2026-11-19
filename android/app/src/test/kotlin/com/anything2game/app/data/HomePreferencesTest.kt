package com.anything2game.app.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePreferencesTest {
    @Test fun `local profile and tab survive a new preferences instance`() {
        val context = RuntimeEnvironment.getApplication()
        val first = HomePreferences(context)
        first.nickname = "  Reader  "
        first.lastTab = "PROFILE"
        val reopened = HomePreferences(context)
        assertEquals("Reader", reopened.nickname)
        assertEquals("PROFILE", reopened.lastTab)
        reopened.nickname = ""
        assertEquals("", HomePreferences(context).nickname)
    }
    @Test fun `preparation is versioned and old choices remain available`() {
        val prefs = HomePreferences(RuntimeEnvironment.getApplication())
        prefs.setFamiliarity("p", "1", "l", Familiarity.UNSURE)
        assertNull(prefs.familiarity("p", "2", "l"))
        prefs.setFamiliarity("p", "2", "l", Familiarity.KNOWN)
        assertEquals(Familiarity.UNSURE, prefs.familiarity("p", "1", "l"))
        assertEquals(Familiarity.KNOWN, prefs.familiarity("p", "2", "l"))
    }
    @Test fun `skipping self assessment marks only that content version prepared`() {
        val prefs = HomePreferences(RuntimeEnvironment.getApplication())
        assertFalse(prefs.isPrepared("p", "1"))
        prefs.markPrepared("p", "1")
        assertTrue(prefs.isPrepared("p", "1"))
        assertFalse(prefs.isPrepared("p", "2"))
        assertNull(prefs.familiarity("p", "1", "@prepared"))
    }
    @Test fun `compound identifiers cannot collide`() {
        val prefs = HomePreferences(RuntimeEnvironment.getApplication())
        prefs.setFamiliarity("p:a", "1", "l", Familiarity.NEW)
        assertNull(prefs.familiarity("p", "a:1", "l"))
    }
    @Test fun `editing profile does not change previous character or frame preferences`() {
        val context = RuntimeEnvironment.getApplication()
        SharedPrefsAvatarPreferences(context).set("pip")
        SharedPrefsFramePreferences(context).set("p", com.anything2game.contract.FrameId.CASE)
        HomePreferences(context).nickname = "New name"
        assertEquals("pip", SharedPrefsAvatarPreferences(context).get())
        assertEquals(com.anything2game.contract.FrameId.CASE, SharedPrefsFramePreferences(context).get("p"))
    }
}
