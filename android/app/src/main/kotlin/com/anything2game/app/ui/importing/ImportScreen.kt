package com.anything2game.app.ui.importing

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.theme.A2g

/**
 * Confirms what was shared before anything leaves the phone. Until the server is connected,
 * the generation information action only says so — it never pretends to generate.
 */
@Composable
fun ImportContent(request: ImportRequest, onCancel: () -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    val skin = A2g.skin
    val context = LocalContext.current
    var attempted by rememberSaveable(request) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.import_received_title), style = MaterialTheme.typography.headlineSmall, color = skin.ink)
        Card(colors = CardDefaults.cardColors(containerColor = skin.card)) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (request) {
                    is ImportRequest.PdfFile -> {
                        Badge(stringResource(R.string.import_kind_pdf), skin.action, skin.paper)
                        Text(request.title, style = MaterialTheme.typography.titleMedium, color = skin.ink)
                        Text(
                            request.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: stringResource(R.string.import_size_unknown),
                            color = skin.inkMuted,
                        )
                    }
                    is ImportRequest.SharedText -> {
                        Badge(stringResource(R.string.import_kind_text), skin.action, skin.paper)
                        Text(request.title, style = MaterialTheme.typography.titleMedium, color = skin.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.import_chars, request.text.length), color = skin.inkMuted)
                        Text(request.text, maxLines = 6, overflow = TextOverflow.Ellipsis, color = skin.ink)
                    }
                }
            }
        }
        Text(stringResource(R.string.import_consent), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
        if (attempted) {
            Card(colors = CardDefaults.cardColors(containerColor = skin.evidenceSoft)) {
                Text(stringResource(R.string.import_not_connected), Modifier.padding(16.dp), color = skin.ink)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) }
            Button(onClick = { attempted = true }, enabled = !attempted) { Text(stringResource(R.string.import_start)) }
        }
    }
}
