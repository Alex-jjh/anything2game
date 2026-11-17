package com.anything2game.app.ui.play.mechanics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anything2game.app.ui.theme.A2g

/** One graded part (card, option): ✓/✗, what it was, and why. Shared by all renderers. */
@Composable
internal fun ReviewRow(correct: Boolean, title: String, detail: String?, explanation: String?) {
    val skin = A2g.skin
    Card(colors = CardDefaults.cardColors(containerColor = if (correct) skin.correctSoft else skin.wrongSoft)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (correct) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                tint = if (correct) skin.correct else skin.wrong,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = skin.ink)
                detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = skin.inkMuted) }
                explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = skin.ink) }
            }
        }
    }
}
