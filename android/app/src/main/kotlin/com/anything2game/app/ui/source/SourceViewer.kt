package com.anything2game.app.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anything2game.app.R
import com.anything2game.app.data.source.Original
import com.anything2game.app.data.source.SourceFile
import com.anything2game.app.data.source.SourceKey
import com.anything2game.app.data.source.SourceLibrary
import com.anything2game.app.data.source.SourceText
import com.anything2game.contract.SourceRef
import kotlinx.coroutines.launch

/** The one test tag on the viewer's root, whichever screen opened it and whichever renderer is inside. */
internal const val SOURCE_VIEWER_TAG = "source_viewer"

private val Chrome = Color(0xFF3C4043)
private val Backdrop = Color(0xFF5F6368)

/** Looks [key] up in [library]; null while that runs, which [SourceViewer] shows as its loading frame. */
@Composable
internal fun rememberSourceFile(library: SourceLibrary, key: SourceKey, text: SourceText): SourceFile? {
    val file by produceState<SourceFile?>(null, library, key, text) { value = library.open(key, text) }
    return file
}

/**
 * The one place a source is shown, from Practice, the explainer, Materials, a route step or a game: the original file
 * page by page when the phone has it ([OriginalPdfRenderer]), the text taken from it, plainly labelled, when not
 * ([ExtractedTextRenderer]). [refs] are the cited passages (marked), [startPage] the 0-based page to open on if the caller
 * knows one; otherwise it opens on the first cited page. A null [file] is the frame with a spinner, so a tap answers at once.
 */
@Composable
internal fun SourceViewer(file: SourceFile?, refs: List<SourceRef> = emptyList(), startPage: Int? = null, onDismiss: () -> Unit, loadingTitle: String = "") {
    var attached by remember(file) { mutableStateOf<Original?>(null) }
    // Held here, outside the dialog: the picker's result must arrive after the activity is recreated behind it.
    val flow = file?.attach?.let { offer -> rememberAttachFlow(offer.expected, offer.run) { attached = Original.Pdf(it) } }
    val title = file?.title ?: loadingTitle
    val original = attached ?: file?.original
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag(SOURCE_VIEWER_TAG), color = Backdrop) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                when {
                    file == null -> { TopBar(title, null, onDismiss); Loading() }
                    original is Original.Pdf -> {
                        val opened = rememberOpenedPages(original.document)
                        when (opened) {
                            Opened.Loading -> { TopBar(title, null, onDismiss); Loading() }
                            Opened.Failed -> TextPages(file, refs, startPage, title, stringResource(R.string.document_open_failed), null, onDismiss)
                            is Opened.Ready -> {
                                val renderer = remember(opened, file.chunks, refs, startPage) { OriginalPdfRenderer(opened.pages, original.document, file.chunks, refs, startPage) }
                                key(renderer) { Pages(renderer, title, null, null, onDismiss) }
                            }
                        }
                    }
                    else -> TextPages(file, refs, startPage, title, stringResource(R.string.source_text_only_note), flow, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TextPages(file: SourceFile, refs: List<SourceRef>, startPage: Int?, title: String, note: String, flow: AttachFlow?, onDismiss: () -> Unit) {
    val renderer = remember(file.chunks, refs, startPage) { ExtractedTextRenderer(file.chunks, refs, startPage) }
    key(renderer) { Pages(renderer, title, note, flow, onDismiss) }
}

/** The frame around any renderer: title, "3 / 15", the pages, prev/next. [note] is the one sentence above the pages; [attach] the "Attach your PDF" row. */
@Composable
private fun ColumnScope.Pages(renderer: SourceRenderer, title: String, note: String?, attach: AttachFlow?, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = renderer.startPage.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))) { renderer.pageCount }
    TopBar(title, if (renderer.pageCount > 0) stringResource(R.string.document_page_of, pager.currentPage + 1, renderer.pageCount) else null, onDismiss)
    if (note != null) Text(note, Modifier.fillMaxWidth().background(Color(0xFF4A4E52)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("source_text_note"),
        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
    if (renderer.pageCount == 0) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.document_no_text), color = Color.White, modifier = Modifier.testTag("source_no_text"))
    } else HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1, userScrollEnabled = renderer.swipeable) { i ->
        renderer.Page(i, current = i == pager.currentPage, modifier = Modifier.fillMaxSize())
    }
    attach?.let { AttachBar(it) }
    BottomBar(pager, renderer.legend)
}

@Composable
private fun ColumnScope.Loading() {
    val loading = stringResource(R.string.source_pdf_loading)
    Box(Modifier.weight(1f).fillMaxWidth().testTag("source_loading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = Color.White)
    }
}

/** Back arrow, the file's name, and "3 / 15" once the pages are known. */
@Composable
private fun TopBar(title: String, pageLabel: String?, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Chrome).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("source_close")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("source_title"))
        if (pageLabel != null) Text(pageLabel, style = MaterialTheme.typography.labelLarge, color = Color.White,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp).testTag("source_page_label"))
    }
}

/** Prev / Next (48dp) beside the marker legend, when something on the pages is marked. */
@Composable
private fun BottomBar(pager: PagerState, legend: Boolean) {
    val scope = rememberCoroutineScope()
    val count = pager.pageCount
    Row(Modifier.fillMaxWidth().background(Chrome).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("source_prev")) {
            Text("◂ " + stringResource(R.string.source_prev_page), color = if (pager.currentPage > 0) Color.White else Color.White.copy(alpha = 0.4f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (legend) {
                Box(Modifier.size(width = 14.dp, height = 10.dp).background(Marker, RoundedCornerShape(2.dp)))
                Text("  " + stringResource(R.string.source_marker_note), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < count - 1, modifier = Modifier.heightIn(min = 48.dp).testTag("source_next")) {
            Text(stringResource(R.string.source_next_page) + " ▸", color = if (pager.currentPage < count - 1) Color.White else Color.White.copy(alpha = 0.4f))
        }
    }
}

/** What a screen writes to open a source: the lookup and the viewer in one call, so every entry point behaves the same. */
@Composable
internal fun OpenSourceViewer(library: SourceLibrary, key: SourceKey, text: SourceText, refs: List<SourceRef> = emptyList(), startPage: Int? = null, onDismiss: () -> Unit) {
    SourceViewer(rememberSourceFile(library, key, text), refs, startPage, onDismiss, loadingTitle = text.title)
}
