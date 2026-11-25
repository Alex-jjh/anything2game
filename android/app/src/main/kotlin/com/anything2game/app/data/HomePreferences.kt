package com.anything2game.app.data

import android.content.Context
import androidx.core.content.edit

/** Device-only profile settings. No account or inferred knowledge score. */
class HomePreferences(context: Context, name: String = "home") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    var nickname: String
        get() = prefs.getString("nickname", "").orEmpty()
        set(value) { prefs.edit { putString("nickname", value.trim().take(24)) } }
    /** Opt-in: pause a level when the phone says the player is walking (off by default). */
    var pauseWhenWalking: Boolean
        get() = prefs.getBoolean("pause_walking", false)
        set(value) { prefs.edit { putBoolean("pause_walking", value) } }
    var lastTab: String
        get() = prefs.getString("tab", "SPACES") ?: "SPACES"
        set(value) { prefs.edit { putString("tab", value) } }

    var lastGame: com.anything2game.contract.game.ContentVersion?
        get() {
            val id = prefs.getString("game.id", null) ?: return null
            val version = prefs.getString("game.version", null) ?: return null
            return com.anything2game.contract.game.ContentVersion(id, version)
        }
        set(value) { prefs.edit { putString("game.id", value?.contentId); putString("game.version", value?.contentVersion) } }

    /**
     * Stars that older versions kept here. They now live in the learning ledger ([com.anything2game.app.data.learning.LearningProgress]),
     * which moves these into it on first read; nothing writes here any more.
     */
    fun legacyStars(game: com.anything2game.contract.game.ContentVersion): Int = prefs.getInt(starKey(game), 0)
    private fun starKey(game: com.anything2game.contract.game.ContentVersion) = "stars:" + keyOf(game)

    /** Whether the player has already been through (or skipped) a game's "Quick look". Kept per profile. */
    fun introSeen(game: com.anything2game.contract.game.ContentVersion): Boolean = prefs.getBoolean("intro:" + keyOf(game), false)
    fun markIntroSeen(game: com.anything2game.contract.game.ContentVersion) { prefs.edit { putBoolean("intro:" + keyOf(game), true) } }
    private fun keyOf(game: com.anything2game.contract.game.ContentVersion) = listOf(game.contentId, game.contentVersion).joinToString(":") { "${it.length}:$it" }
}
