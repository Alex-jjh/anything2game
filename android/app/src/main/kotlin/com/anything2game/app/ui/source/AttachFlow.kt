package com.anything2game.app.ui.source

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.data.tutor.AttachResult
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.app.importing.IntentImportParser
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Attaching a course's PDF (ADR-019), held by the composition that shows the viewer rather than the dialog: the picker's
 * result is registered outside the dialog, so it still arrives after the activity is recreated behind the system picker,
 * and a check that has started runs to the end even if the viewer is closed meanwhile, because an accepted file is kept
 * on the phone and the next look at the source finds it.
 */
@Stable
internal class AttachFlow(private val scope: CoroutineScope, private val run: suspend (open: () -> InputStream?) -> AttachResult, private val onAttached: (SourceDocument) -> Unit) {
    var state by mutableStateOf<AttachState>(AttachState.Idle)
        private set
    internal var pick: () -> Unit = {}

    fun picked(open: () -> InputStream?) {
        if (state == AttachState.Checking) return
        state = AttachState.Checking
        scope.launch {
            val r = try { withContext(NonCancellable) { run(open) } } catch (e: CancellationException) { throw e } catch (e: Exception) { AttachResult.Unreadable }
            state = AttachState.Done(r)
            if (r is AttachResult.Accepted) onAttached(r.document)
        }
    }

    /** A new look at the source starts from the note again; a check still running keeps going. */
    fun reset() { if (state is AttachState.Done) state = AttachState.Idle }
}

/** The [AttachFlow] for one course, with its system file picker; call it where the screen lives, not inside the viewer. */
@Composable
internal fun rememberAttachFlow(key: Any?, attach: suspend (open: () -> InputStream?) -> AttachResult, onAttached: (SourceDocument) -> Unit = {}): AttachFlow {
    val scope = rememberCoroutineScope()
    val run by rememberUpdatedState(attach)
    val attached by rememberUpdatedState(onAttached)
    val flow = remember(key, scope) { AttachFlow(scope, { run(it) }, { attached(it) }) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) flow.picked { context.contentResolver.openInputStream(uri) }
    }
    flow.pick = { launcher.launch(arrayOf(IntentImportParser.PDF)) }
    return flow
}

/** Where an attachment stands. */
internal sealed interface AttachState {
    data object Idle : AttachState
    data object Checking : AttachState
    class Done(val result: AttachResult) : AttachState
}

/**
 * One quiet row above the page controls: a footnote for readers who have the paper, nothing a reader without it must
 * act on. The picker copies the file once (no lasting grant); the outcome replaces the note, and a refusal says why.
 * The text is capped and scrolls, so a long refusal at large font sizes never squeezes the page away.
 */
@Composable
internal fun AttachBar(flow: AttachFlow) {
    val context = LocalContext.current
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    val state = flow.state
    val checking = state == AttachState.Checking
    val done = (state as? AttachState.Done)?.result
    val message = when (done) {
        null -> stringResource(if (checking) R.string.source_attach_checking else R.string.source_attach_note)
        is AttachResult.Accepted -> stringResource(R.string.source_attach_checking)
        is AttachResult.Mismatch -> stringResource(R.string.source_attach_mismatch, done.expected.pdfSource ?: stringResource(R.string.source_attach_mismatch_unknown), size(done.expected.pdfBytes), size(done.actualBytes))
        AttachResult.TooLarge -> stringResource(R.string.source_attach_too_large)
        AttachResult.NotPdf -> stringResource(R.string.source_attach_not_pdf)
        AttachResult.Unreadable, AttachResult.NoLayout -> stringResource(R.string.source_attach_unreadable)
    }
    val refused = done != null && done !is AttachResult.Accepted
    // Where the right file can be fetched: a button, not a URL to retype from a dialog.
    val download = (done as? AttachResult.Mismatch)?.expected?.pdfSource?.takeIf { it.startsWith("https://") }
    val description = stringResource(R.string.source_attach_desc)
    Row(Modifier.fillMaxWidth().background(Color(0xFF4A4E52)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).heightIn(max = 96.dp).verticalScroll(rememberScrollState())) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = if (refused) Color(0xFFFFD7A8) else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(if (refused) "source_attach_refused" else "source_attach_status"))
        }
        if (done !is AttachResult.Accepted) Column(horizontalAlignment = Alignment.End) {
            if (download != null) TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(download))) } catch (e: ActivityNotFoundException) {} },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("source_attach_download")) {
                Text(stringResource(R.string.source_attach_download), color = Color.White, fontWeight = FontWeight.Medium)
            }
            // Kept in place but disabled while checking, so TalkBack focus is not lost under the reader.
            TextButton(onClick = flow.pick, enabled = !checking, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).testTag("source_attach_pdf")
                .semantics { contentDescription = description }) {
                Text(stringResource(if (refused) R.string.source_attach_retry else R.string.source_attach_action), color = if (checking) Color.White.copy(alpha = 0.4f) else Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

