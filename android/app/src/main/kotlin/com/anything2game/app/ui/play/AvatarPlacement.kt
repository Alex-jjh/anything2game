package com.anything2game.app.ui.play

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.ui.character.AvatarCharacter
import com.anything2game.app.ui.character.AvatarSignals
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.theme.A2g
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SlotKind

/**
 * Game events → the character's mood. While a question is open the mood comes only from the player's own input
 * (lean, hold, picked statement, hints) — never from the answer key, so the character can't give the answer away.
 */
internal fun avatarMood(state: PlayUiState.Ready, signals: AvatarSignals): Mood {
    val s = state.session
    val phase = s.phase
    return when {
        s.isPaused -> Mood.SLEEPY
        phase == Phase.Failed -> Mood.DEFLATED
        state.recap != null -> if (state.recap.bossCleared == false) Mood.DEFLATED else Mood.CELEBRATE
        phase is Phase.Feedback -> {
            val g = phase.grade
            val partly = !g.correct && g.parts > 1 && g.wrongPartIds.size < g.parts
            when {
                g.correct && s.currentSlot?.kind == SlotKind.BOSS -> Mood.OBJECT
                g.correct -> Mood.HAPPY
                partly -> Mood.DOUBT
                else -> Mood.DEFLATED
            }
        }
        state.scene != null -> Mood.IDLE
        signals.charge > 0.02f -> Mood.CHARGING
        signals.focus != null -> signals.focus!!
        s.hintsShown > 0 -> Mood.THINKING
        else -> Mood.IDLE
    }
}

/** One reaction line in the character's own voice, picked stably per slot so it doesn't flicker. */
@Composable
internal fun avatarLine(avatar: AvatarCharacter, mood: Mood, seed: Int): String? {
    val res = avatar.linesFor(mood) ?: return null
    val lines = stringArrayResource(res)
    return lines.getOrNull(Math.floorMod(seed, lines.size.coerceAtLeast(1)))
}

/** A speech bubble whose tail points up-left, at the character in the header. */
@Composable
internal fun SpeechBubble(avatar: AvatarCharacter, text: String, modifier: Modifier = Modifier) {
    val skin = A2g.skin
    Box(modifier.fillMaxWidth()) {
        Surface(
            Modifier.padding(start = 22.dp).size(width = 18.dp, height = 10.dp),
            shape = GenericShape { size, _ -> moveTo(0f, size.height); lineTo(size.width * 0.35f, 0f); lineTo(size.width, size.height); close() },
            color = skin.card,
        ) {}
        Surface(
            Modifier.padding(top = 9.dp),
            shape = RoundedCornerShape(16.dp), color = skin.card, border = BorderStroke(1.dp, skin.outline.copy(alpha = 0.5f)),
        ) {
            Text(
                buildString { append(stringResource(avatar.nameRes)); append(": "); append(text) },
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = skin.ink, fontWeight = FontWeight.Medium,
            )
        }
    }
}
