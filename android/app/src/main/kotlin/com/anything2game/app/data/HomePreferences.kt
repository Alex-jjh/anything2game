package com.anything2game.app.data

import android.content.Context
import androidx.core.content.edit

/** Device-only profile and reading preparation. No account or inferred knowledge score. */
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

    /** The best star count ever earned on a game (0 = none yet). Kept per profile; a new attempt never lowers it. */
    fun bestStars(game: com.anything2game.contract.game.ContentVersion): Int = prefs.getInt(starKey(game), 0)
    fun recordStars(game: com.anything2game.contract.game.ContentVersion, stars: Int) {
        if (stars > bestStars(game)) prefs.edit { putInt(starKey(game), stars.coerceIn(0, 3)) }
    }
    private fun starKey(game: com.anything2game.contract.game.ContentVersion) = "stars:" + listOf(game.contentId, game.contentVersion).joinToString(":") { "${it.length}:$it" }

    /** Whether the player has already been through (or skipped) a game's "Quick look". Kept per profile. */
    fun introSeen(game: com.anything2game.contract.game.ContentVersion): Boolean = prefs.getBoolean("intro:" + keyOf(game), false)
    fun markIntroSeen(game: com.anything2game.contract.game.ContentVersion) { prefs.edit { putBoolean("intro:" + keyOf(game), true) } }
    private fun keyOf(game: com.anything2game.contract.game.ContentVersion) = listOf(game.contentId, game.contentVersion).joinToString(":") { "${it.length}:$it" }

    // Length prefixes avoid collisions; versioning retains old answers without applying them to new content.
    private fun key(pkg: String, version: String, level: String) =
        listOf("familiarity", pkg, version, level).joinToString(":") { "${it.length}:$it" }
    fun isPrepared(pkg: String, version: String): Boolean = prefs.getBoolean("prepared:" + key(pkg, version, ""), false)
    fun markPrepared(pkg: String, version: String) { prefs.edit { putBoolean("prepared:" + key(pkg, version, ""), true) } }
    fun familiarity(pkg: String, version: String, level: String): Familiarity? =
        prefs.getString(key(pkg, version, level), null)?.let { raw -> Familiarity.entries.firstOrNull { it.name == raw } }
    fun setFamiliarity(pkg: String, version: String, level: String, value: Familiarity) {
        prefs.edit { putString(key(pkg, version, level), value.name) }
    }
}

enum class Familiarity { KNOWN, UNSURE, NEW }
