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
