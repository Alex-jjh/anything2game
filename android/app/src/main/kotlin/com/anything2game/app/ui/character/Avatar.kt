package com.anything2game.app.ui.character

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * What the player's character is feeling. Derived from game events by the play screen (never from the answer key
 * while a question is open — the character must not give the answer away).
 */
enum class Mood { IDLE, HAPPY, DEFLATED, DOUBT, THINKING, CHARGING, OBJECT, CELEBRATE, SLEEPY, SURPRISED }

/**
 * Live input from the mechanic renderers to the character, frame by frame: how far the swipe card leans,
 * how far the hold-to-object button has filled, and a mood the renderer asks for (e.g. doubt while a statement is picked).
 */
@Stable
class AvatarSignals {
    var lean by mutableFloatStateOf(0f)
    var charge by mutableFloatStateOf(0f)
    var focus by mutableStateOf<Mood?>(null)
}

val LocalAvatarSignals = compositionLocalOf { AvatarSignals() }

/**
 * A playable character. The swap point for the team's art: a procedural blob, a set of expression images
 * ([SpriteAvatar]) or a Rive state machine all implement this, and nothing else in the app changes.
 */
interface AvatarCharacter {
    val id: String
    @get:StringRes val nameRes: Int
    @get:StringRes val traitRes: Int
    /** Short reaction lines for a mood (string-array resource), or null for none. */
    @ArrayRes fun linesFor(mood: Mood): Int?

    /** [lookAt] in -1..1 on both axes; [useTilt] lets the eyes follow the phone's tilt (gravity sensor). */
    @Composable
    fun Draw(mood: Mood, modifier: Modifier = Modifier, lookAt: Offset = Offset.Zero, useTilt: Boolean = false)
}

val LocalAvatar = compositionLocalOf<AvatarCharacter> { Avatars.default }
