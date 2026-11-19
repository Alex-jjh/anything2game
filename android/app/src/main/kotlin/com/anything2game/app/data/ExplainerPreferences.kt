package com.anything2game.app.data

import android.content.Context

/** Which look the explainer scene uses (theme id). A UI preference per profile, like [AvatarPreferences]. */
interface ExplainerPreferences {
    fun get(): String?
    fun set(themeId: String)
}

class SharedPrefsExplainerPreferences(context: Context, name: String = "explainer") : ExplainerPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(): String? = prefs.getString("theme", null)
    override fun set(themeId: String) { prefs.edit().putString("theme", themeId).apply() }
}
