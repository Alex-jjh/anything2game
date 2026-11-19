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
internal fun RecapContent(recap: RecapSummary, unitCount: Int, title: String, frameId: com.anything2game.contract.FrameId = com.anything2game.contract.FrameId.CLASSIC, onReplay: () -> Unit, onLibrary: () -> Unit, hero: @Composable () -> Unit = {}, extra: @Composable () -> Unit = {}) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (frameId == com.anything2game.contract.FrameId.CASE) {
            // The one orchestrated moment of the level: the case-closed stamp lands.
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                com.anything2game.app.ui.common.Stamp(stringResource(R.string.stamp_case_closed), com.anything2game.app.ui.common.StampKind.CLOSED, com.anything2game.app.ui.theme.StampRed, big = true)
            }
        } else {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = skin.ink)
        }
        hero()
        Text(stringResource(R.string.recap_score, recap.solidUnitIds.size, unitCount), style = MaterialTheme.typography.titleMedium, color = skin.action)
        // Outcome first (what you caught, what comes back), then the takeaways (D8).
        recap.bossCleared?.let { cleared ->
            Card(colors = CardDefaults.cardColors(containerColor = if (cleared) skin.correctSoft else skin.wrongSoft)) {
                Text(
                    stringResource(if (cleared) R.string.boss_cleared else R.string.boss_missed),
                    Modifier.fillMaxWidth().padding(16.dp), color = if (cleared) skin.correct else skin.wrong,
                    style = MaterialTheme.typography.titleMedium,
                )
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
        extra()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onLibrary, Modifier.weight(1f)) { Text(stringResource(R.string.recap_library)) }
            Button(onClick = onReplay, Modifier.weight(1f)) { Text(stringResource(R.string.recap_replay)) }
        }
    }
}
