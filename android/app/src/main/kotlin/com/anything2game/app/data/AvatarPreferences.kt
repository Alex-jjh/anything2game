package com.anything2game.app.data

import android.content.Context

/** Which character the player picked. A UI preference, like [FramePreferences]. */
interface AvatarPreferences {
    fun get(): String?
    fun set(avatarId: String)
}

class SharedPrefsAvatarPreferences(context: Context, name: String = "avatar") : AvatarPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(): String? = prefs.getString("id", null)
    override fun set(avatarId: String) { prefs.edit().putString("id", avatarId).apply() }
}
