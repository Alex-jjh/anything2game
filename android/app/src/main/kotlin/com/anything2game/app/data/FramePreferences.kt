package com.anything2game.app.data

import android.content.Context
import com.anything2game.contract.FrameId

/** Which frame the player chose for each package. A UI preference, so it lives in :app, not the engine. */
interface FramePreferences {
    fun get(packageId: String): FrameId?
    fun set(packageId: String, frameId: FrameId)
}

class SharedPrefsFramePreferences(context: Context) : FramePreferences {
    private val prefs = context.applicationContext.getSharedPreferences("frames", Context.MODE_PRIVATE)
    override fun get(packageId: String): FrameId? = prefs.getString(packageId, null)?.let { v -> FrameId.entries.firstOrNull { it.name == v } }
    override fun set(packageId: String, frameId: FrameId) { prefs.edit().putString(packageId, frameId.name).apply() }
}

/** Default when the player hasn't chosen: the full experience if available. */
val DEFAULT_FRAME = FrameId.CASE
