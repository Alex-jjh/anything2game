package com.anything2game.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.ContextSignals.Activity
import com.anything2game.engine.context.ContextSignals.Network

/** Debug panel that drives the simulated context source (no emulator or sensors needed). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextSimulator(
    signals: ContextSignals,
    onActivity: (Activity) -> Unit,
    onNetwork: (Network) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sim_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Activity.STILL to R.string.sim_still, Activity.WALKING to R.string.sim_walking, Activity.IN_VEHICLE to R.string.sim_vehicle)
                    .forEach { (a, label) -> FilterChip(signals.activity == a, { onActivity(a) }, { Text(stringResource(label)) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Network.UNMETERED to R.string.sim_online, Network.OFFLINE to R.string.sim_offline)
                    .forEach { (n, label) -> FilterChip(signals.network == n, { onNetwork(n) }, { Text(stringResource(label)) }) }
            }
        }
    }
}
