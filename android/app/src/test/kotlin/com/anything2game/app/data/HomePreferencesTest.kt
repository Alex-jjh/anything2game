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
    @Test fun `stars an older version kept are still readable and never written again`() {
        val context = RuntimeEnvironment.getApplication()
        val game = com.anything2game.contract.game.ContentVersion("locker-search-en", "1")
        context.getSharedPreferences("home", android.content.Context.MODE_PRIVATE).edit().putInt("stars:" + listOf(game.contentId, game.contentVersion).joinToString(":") { "${it.length}:$it" }, 2).commit()
        assertEquals(2, HomePreferences(context).legacyStars(game))
        assertEquals(0, HomePreferences(context).legacyStars(com.anything2game.contract.game.ContentVersion("workshop-en", "1")))
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
