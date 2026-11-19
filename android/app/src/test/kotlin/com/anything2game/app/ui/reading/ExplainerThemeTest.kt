package com.anything2game.app.ui.reading

import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.SharedPrefsExplainerPreferences
import com.anything2game.app.data.profile.LocalProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExplainerThemeTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun `without bundled art only the paper theme is offered`() {
        assertEquals(listOf("paper"), ExplainerThemes.available(app).map { it.id })
        assertSame(PaperExplainerTheme, ExplainerThemes.resolve(app, "illustrated"))
        assertSame(PaperExplainerTheme, ExplainerThemes.resolve(app, null))
        assertSame(PaperExplainerTheme, ExplainerThemes.resolve(app, "no-such-theme"))
    }

    @Test fun `the illustrated theme appears as soon as a standee_idle drawable exists`() {
        val none = IllustratedExplainerTheme { _, _ -> 0 }
        val art = IllustratedExplainerTheme { _, name -> if (name == "standee_idle") 7 else 0 }
        assertFalse(none.isAvailable(app))
        assertTrue(art.isAvailable(app))
        val themes = listOf(PaperExplainerTheme, art)
        assertEquals(listOf("paper", "illustrated"), ExplainerThemes.available(app, themes).map { it.id })
        assertSame(art, ExplainerThemes.resolve(app, "illustrated", themes))
    }

    @Test fun `the choice is kept per profile`() = runBlocking {
        val prefs = SharedPrefsExplainerPreferences(app, "explainer")
        assertNull(prefs.get())
        prefs.set("illustrated")
        assertEquals("illustrated", SharedPrefsExplainerPreferences(app, "explainer").get())

        val deps = AndroidAppContainer(app)
        deps.profiles.choose(LocalProfile.GUEST)
        assertEquals("illustrated", deps.explainerPreferences.get())
        deps.profiles.choose(LocalProfile.SAMPLE)
        assertNull("the sample student has their own preference", deps.explainerPreferences.get())
        deps.explainerPreferences.set("paper")
        deps.profiles.choose(LocalProfile.GUEST)
        assertEquals("illustrated", deps.explainerPreferences.get())
    }
}
