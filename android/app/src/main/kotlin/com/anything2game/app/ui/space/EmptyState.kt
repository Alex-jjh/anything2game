package com.anything2game.app.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.theme.A2g

/** A tab with nothing on it yet: the player's character, one sentence (and optionally a second), and the one clear next action in [actions]. */
@Composable
internal fun EmptyState(tag: String, title: String, body: String? = null, actions: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(132.dp).background(A2g.skin.evidenceSoft, CircleShape), contentAlignment = Alignment.Center) {
            LocalAvatar.current.Draw(Mood.IDLE, Modifier.size(104.dp), pokeable = false)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) Text(body, color = A2g.skin.inkMuted, textAlign = TextAlign.Center)
        actions()
    }
}
