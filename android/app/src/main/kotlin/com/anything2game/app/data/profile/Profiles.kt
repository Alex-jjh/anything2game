package com.anything2game.app.data.profile

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * A local profile: who is using the app on this phone. Guest and the sample student are the two kinds today;
 * a signed-in account (Cognito, ADR-014) becomes a third kind behind the same [ProfileController] later.
 * Every profile has its own progress, mastery, tutor sessions and preferences; content packs are shared.
 */
enum class ProfileKind { GUEST, SAMPLE }

data class LocalProfile(val id: String, val kind: ProfileKind) {
    /** The guest keeps the original locations, so data from before profiles existed stays where it was. */
    fun dir(context: Context): File = if (kind == ProfileKind.GUEST) context.filesDir else File(context.filesDir, "profiles/$id")
    fun prefs(base: String): String = if (kind == ProfileKind.GUEST) base else "$base@$id"

    companion object {
        val GUEST = LocalProfile("guest", ProfileKind.GUEST)
        val SAMPLE = LocalProfile("sample", ProfileKind.SAMPLE)
        val all = listOf(SAMPLE, GUEST)
        fun of(id: String?): LocalProfile? = all.firstOrNull { it.id == id }
    }
}

/** Choosing who plays. Screens use only this; the container swaps every per-user store behind the same interfaces. */
interface ProfileController {
    /** Null until the player has chosen (the welcome screen shows). */
    val active: StateFlow<LocalProfile?>
    /** Activates [profile]; the sample student's history is created on first use (through the normal stores). */
    suspend fun choose(profile: LocalProfile)
    /** Back to the welcome screen. Nothing is deleted. */
    fun leave()
}

/** Remembers the active profile across launches. */
class ProfileRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    fun active(): LocalProfile? = LocalProfile.of(prefs.getString("active", null))
    fun setActive(p: LocalProfile?) { prefs.edit().apply { if (p == null) remove("active") else putString("active", p.id) }.apply() }
    fun isSeeded(p: LocalProfile): Boolean = prefs.getBoolean("seeded:${p.id}", false)
    fun markSeeded(p: LocalProfile) { prefs.edit().putBoolean("seeded:${p.id}", true).apply() }
}
