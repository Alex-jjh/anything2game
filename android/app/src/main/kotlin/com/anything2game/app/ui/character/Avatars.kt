package com.anything2game.app.ui.character

import androidx.compose.ui.graphics.Color
import com.anything2game.app.R

/**
 * The characters a player can pick. Placeholders (original designs) until the team's art lands:
 * add a [SpriteAvatar] or another [AvatarCharacter] here and it shows up in the picker.
 */
object Avatars {
    val MOCHI = BlobAvatar(
        "mochi", R.string.avatar_mochi, R.string.avatar_mochi_trait,
        BlobLook(body = Color(0xFFFFD6BF), outline = Color(0xFFB0614A), cheek = Color(0xFFF08A7A), accent = Color(0xFF6B9A4F), shape = BlobShape.ROUND, topper = Topper.LEAF),
        lines("mochi"),
    )
    val PIP = BlobAvatar(
        "pip", R.string.avatar_pip, R.string.avatar_pip_trait,
        BlobLook(body = Color(0xFF8FD0C8), outline = Color(0xFF2E6B66), cheek = Color(0xFFF2998A), accent = Color(0xFFF2B33D), shape = BlobShape.DROP, topper = Topper.ANTENNA),
        lines("pip"),
    )
    val KUMO = BlobAvatar(
        "kumo", R.string.avatar_kumo, R.string.avatar_kumo_trait,
        BlobLook(body = Color(0xFFD9CCF5), outline = Color(0xFF6650A6), cheek = Color(0xFFF29AB8), accent = Color(0xFF7FB7E8), shape = BlobShape.CLOUD, topper = Topper.NONE),
        lines("kumo"),
    )

    val all: List<AvatarCharacter> = listOf(MOCHI, PIP, KUMO)
    val default: AvatarCharacter get() = MOCHI
    fun of(id: String?): AvatarCharacter = all.firstOrNull { it.id == id } ?: default

    private fun lines(id: String): Map<Mood, Int> = when (id) {
        "mochi" -> mapOf(Mood.HAPPY to R.array.avatar_mochi_happy, Mood.DEFLATED to R.array.avatar_mochi_deflated, Mood.DOUBT to R.array.avatar_mochi_doubt,
            Mood.THINKING to R.array.avatar_mochi_thinking, Mood.OBJECT to R.array.avatar_mochi_object, Mood.CELEBRATE to R.array.avatar_mochi_celebrate, Mood.SLEEPY to R.array.avatar_mochi_sleepy)
        "pip" -> mapOf(Mood.HAPPY to R.array.avatar_pip_happy, Mood.DEFLATED to R.array.avatar_pip_deflated, Mood.DOUBT to R.array.avatar_pip_doubt,
            Mood.THINKING to R.array.avatar_pip_thinking, Mood.OBJECT to R.array.avatar_pip_object, Mood.CELEBRATE to R.array.avatar_pip_celebrate, Mood.SLEEPY to R.array.avatar_pip_sleepy)
        else -> mapOf(Mood.HAPPY to R.array.avatar_kumo_happy, Mood.DEFLATED to R.array.avatar_kumo_deflated, Mood.DOUBT to R.array.avatar_kumo_doubt,
            Mood.THINKING to R.array.avatar_kumo_thinking, Mood.OBJECT to R.array.avatar_kumo_object, Mood.CELEBRATE to R.array.avatar_kumo_celebrate, Mood.SLEEPY to R.array.avatar_kumo_sleepy)
    }
}
