package com.anything2game.app.ui.space

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.anything2game.app.R
import com.anything2game.app.data.learning.*
import com.anything2game.app.ui.reading.SourceViewer
import com.anything2game.contract.game.EvidenceRef
import kotlinx.coroutines.CancellationException

@Composable
internal fun ActivityEvidence(catalog: MaterialCatalog, ref: EvidenceRef, material: Material? = null, dismiss: () -> Unit) {
    var target by remember(ref) { mutableStateOf<SourceTarget?>(null) }
    var loading by remember(ref) { mutableStateOf(true) }
    var failed by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(catalog, ref, material) {
        try { target = catalog.source(ref, material) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        loading = false
    }
    val t = target
    if (t is SourceTarget.Pdf) { SourceViewer(t.chunks, listOf(t.ref), t.document, onDismiss = dismiss); return }
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = dismiss, title = { Text(when (t) {
        is SourceTarget.Card -> t.evidence.sourceTitle
        is SourceTarget.External -> t.evidence.title
        else -> stringResource(R.string.source_open)
    }) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        when (t) {
            is SourceTarget.Card -> { Text(t.evidence.section); Text(t.evidence.fact); Text(t.evidence.reviewNote) }
            is SourceTarget.External -> Text(t.evidence.sectionOrExcerpt)
            else -> if (loading) CircularProgressIndicator() else Text(stringResource(R.string.spaces_source_unavailable))
        }
        if (failed && t != null) Text(stringResource(R.string.spaces_source_unavailable))
    } }, confirmButton = {
        val link = when(t) { is SourceTarget.Card -> t.evidence.sourceUrl; is SourceTarget.External -> t.evidence.url; else -> null }
        if (link != null) TextButton({ try { uri.openUri(link) } catch (_: Exception) { failed = true } }) { Text(stringResource(R.string.source_open)) }
    }, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.action_back)) } })
}
