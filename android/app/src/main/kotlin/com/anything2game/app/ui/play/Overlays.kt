package com.anything2game.app.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g

/** Bottom panel over a dimmed screen. In-layout (not a dialog) so state and screenshots stay simple. */
@Composable
internal fun BottomPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = A2g.skin.card),
        ) {
            Column(Modifier.navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
internal fun AskPanel(offline: Boolean, onClose: () -> Unit) {
    val skin = A2g.skin
    BottomPanel(onClose) {
        Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
        Text(
            stringResource(if (offline) R.string.ask_unavailable_offline else R.string.ask_unavailable_server),
            style = MaterialTheme.typography.bodyLarge, color = skin.inkMuted,
        )
        Button(onClick = onClose, Modifier.fillMaxWidth()) { Text(stringResource(R.string.evidence_close)) }
    }
}

/** Shown when the context policy pauses play (walking). The player can override a false detection. */
@Composable
internal fun BoxScope.PausedOverlay(onContinue: () -> Unit, hero: @Composable () -> Unit = {}) {
    val skin = A2g.skin
    Box(Modifier.matchParentSize().background(skin.paper.copy(alpha = 0.97f)).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            hero()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = skin.paused, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.paused_title), style = MaterialTheme.typography.headlineSmall, color = skin.ink)
            }
            Text(stringResource(R.string.paused_body), style = MaterialTheme.typography.bodyLarge, color = skin.inkMuted)
            Button(onClick = onContinue) { Text(stringResource(R.string.paused_continue)) }
        }
    }
}
