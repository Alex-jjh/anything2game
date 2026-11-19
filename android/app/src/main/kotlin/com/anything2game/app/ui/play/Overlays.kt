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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceRef

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

/** The trust feature: every answer opens the exact passage, with the cited words highlighted. */
@Composable
internal fun EvidencePanel(chunks: List<SourceChunk>, refs: List<SourceRef>, documentTitle: String, onClose: () -> Unit) {
    val skin = A2g.skin
    BottomPanel(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Description, null, tint = skin.evidence)
            Text(stringResource(R.string.evidence_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
        }
        Text(documentTitle, style = MaterialTheme.typography.labelLarge, color = skin.inkMuted)
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            chunks.forEach { chunk ->
                Column(
                    Modifier.fillMaxWidth().background(skin.paper, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.evidence_page, chunk.pageLabel ?: (chunk.pageIndex + 1).toString()),
                        style = MaterialTheme.typography.labelMedium, color = skin.evidence, fontWeight = FontWeight.SemiBold,
                    )
                    Text(highlight(chunk.text, refs.filter { it.chunkId == chunk.chunkId }.map { it.quote }, skin.evidenceSoft), style = MaterialTheme.typography.bodyLarge, color = skin.ink)
                }
            }
        }
        Button(onClick = onClose, Modifier.fillMaxWidth()) { Text(stringResource(R.string.evidence_close)) }
    }
}

/** Highlights each quote inside the chunk. Falls back to plain text when a quote spans a line break. */
private fun highlight(text: String, quotes: List<String>, color: Color) = buildAnnotatedString {
    val ranges = quotes.mapNotNull { q ->
        val i = text.indexOf(q).takeIf { it >= 0 } ?: text.indexOf(PackageValidator.normalise(q)).takeIf { it >= 0 }
        i?.let { it until it + q.length }
    }.sortedBy { it.first }
    var cursor = 0
    ranges.forEach { r ->
        if (r.first < cursor) return@forEach
        append(text.substring(cursor, r.first))
        withStyle(SpanStyle(background = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(r.first, r.last + 1)) }
        cursor = r.last + 1
    }
    append(text.substring(cursor))
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
