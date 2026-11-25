package com.anything2game.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g

/** Pick your character. Each one plays live (breathes, blinks, reacts to a poke); the chosen one cheers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(selectedId: String?, onSelect: (String) -> Unit) {
    val skin = A2g.skin
    val current = com.anything2game.app.ui.character.LocalAvatar.current
    androidx.compose.material3.Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = skin.card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.avatar_pick_title), style = MaterialTheme.typography.titleMedium, color = skin.ink)
            Text(stringResource(R.string.avatar_pick_body), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.anything2game.app.ui.character.Avatars.all.forEach { a ->
                    val chosen = a.id == (selectedId ?: current.id)
                    Column(
                        Modifier
                            .border(if (chosen) 2.dp else 1.dp, if (chosen) skin.action else skin.outline.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .selectable(chosen, role = androidx.compose.ui.semantics.Role.RadioButton) { onSelect(a.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        // Only the chosen one can be poked; on the others a tap on the picture must choose them.
                        a.Draw(if (chosen) com.anything2game.app.ui.character.Mood.HAPPY else com.anything2game.app.ui.character.Mood.IDLE, Modifier.size(64.dp), pokeable = chosen)
                        Text(stringResource(a.nameRes), style = MaterialTheme.typography.labelLarge, color = if (chosen) skin.action else skin.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(stringResource(a.traitRes), style = MaterialTheme.typography.labelSmall, color = skin.inkMuted)
                    }
                }
            }
        }
    }
}
