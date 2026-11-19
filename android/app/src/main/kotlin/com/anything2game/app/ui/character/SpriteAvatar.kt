package com.anything2game.app.ui.character

import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource

/**
 * Drop-in for the team's own art: one image per expression (PNG or vector drawable, square, character standing
 * on the bottom edge). The shared motion (breath, squash, hop, lean, poke) is applied on top, so static
 * expression sheets still feel alive. Missing moods fall back to [Mood.IDLE].
 */
class SpriteAvatar(
    override val id: String,
    @StringRes override val nameRes: Int,
    @StringRes override val traitRes: Int,
    private val images: Map<Mood, Int>,
    private val lines: Map<Mood, Int> = emptyMap(),
) : AvatarCharacter {
    init { require(Mood.IDLE in images) { "SpriteAvatar '$id' needs at least an IDLE image" } }
    @ArrayRes override fun linesFor(mood: Mood): Int? = lines[mood]

    @Composable
    override fun Draw(mood: Mood, modifier: Modifier, lookAt: Offset, useTilt: Boolean) {
        val m = rememberAvatarMotion(mood, lookAt, useTilt)
        @DrawableRes val res = images[m.mood] ?: images.getValue(Mood.IDLE)
        Image(
            painterResource(res), contentDescription = null,
            modifier = modifier.aspectRatio(1f).then(m.pokeModifier).graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                val sq = m.squash + m.charge * 0.1f
                scaleX = 1f + sq; scaleY = 1f - m.squash + m.charge * 0.1f + m.breath * 0.025f
                translationY = m.hop * size.height
                translationX = m.lean * size.width * 0.06f
                rotationZ = m.lean * 6f
            },
        )
    }
}
