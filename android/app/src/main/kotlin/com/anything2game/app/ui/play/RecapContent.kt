package com.anything2game.app.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.engine.session.RecapSummary

/** Closing the case: what you now know, and what is still shaky (never "100% = mastered"). */
@Composable
internal fun RecapContent(recap: RecapSummary, unitCount: Int, onReplay: () -> Unit, onLibrary: () -> Unit) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.recap_title), style = MaterialTheme.typography.headlineSmall, color = skin.ink)
        Text(stringResource(R.string.recap_score, recap.solidUnitIds.size, unitCount), style = MaterialTheme.typography.titleMedium, color = skin.action)
        Card(colors = CardDefaults.cardColors(containerColor = skin.card)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                recap.points.forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", color = skin.evidence, style = MaterialTheme.typography.bodyLarge)
                        Text(point, style = MaterialTheme.typography.bodyLarge, color = skin.ink)
                    }
                }
            }
        }
        val shaky = recap.shakyUnitIds.size
        Card(colors = CardDefaults.cardColors(containerColor = if (shaky == 0) skin.correctSoft else skin.hintSoft)) {
            Text(
                if (shaky == 0) stringResource(R.string.recap_all_solid) else stringResource(R.string.recap_shaky, shaky),
                Modifier.fillMaxWidth().padding(16.dp),
                color = if (shaky == 0) skin.correct else skin.hint,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onLibrary, Modifier.weight(1f)) { Text(stringResource(R.string.recap_library)) }
            Button(onClick = onReplay, Modifier.weight(1f)) { Text(stringResource(R.string.recap_replay)) }
        }
    }
}
