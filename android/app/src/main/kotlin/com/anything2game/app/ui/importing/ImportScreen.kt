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
 * Confirms the material and server destination before upload. Generation and retries are owned
 * by ImportViewModel; the fixed example is explicitly a separate document.
 */
@Composable
fun ImportContent(request: ImportRequest, onCancel: () -> Unit, contentPadding: PaddingValues = PaddingValues(),
    endpoint: String = "", state: ImportUiState = ImportUiState(), onStart: () -> Unit = {}, onDemo: (() -> Unit)? = null) {
    val skin = A2g.skin
    val context = LocalContext.current
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
        Text(stringResource(R.string.tutor_upload_consent), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
        Text(endpoint.ifBlank { stringResource(R.string.tutor_no_endpoint) }, style = MaterialTheme.typography.bodySmall, color = skin.action)
        if (state.uploaded) Text(stringResource(R.string.tutor_uploaded), color = skin.action)
        if (state.busy) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(if (state.uploaded) R.string.tutor_preparing else R.string.tutor_uploading), color = skin.inkMuted)
        }
        state.error?.let { Text(stringResource(R.string.tutor_import_error, it), color = skin.wrong) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) }
            Button(onClick = onStart, enabled = !state.busy && endpoint.isNotBlank()) { Text(stringResource(if (state.error == null) R.string.tutor_upload_start else R.string.home_retry)) }
        }
        Text(stringResource(R.string.tutor_import_cancel_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
        if (onDemo != null) {
            Text(stringResource(R.string.tutor_separate_example), color = skin.inkMuted)
            OutlinedButton(onClick = onDemo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tutor_demo_action)) }
        }
    }
}
