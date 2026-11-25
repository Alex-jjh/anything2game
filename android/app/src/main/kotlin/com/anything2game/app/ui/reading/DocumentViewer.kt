package com.anything2game.app.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.data.tutor.AttachResult
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceRef
import kotlinx.coroutines.CancellationException

/**
 * A file of a Space opened full screen, the way a phone's file manager opens it: the original PDF page by page (swipe,
 * pinch to zoom, "3 / 15"), or, when the original is not on this phone, its extracted text with a plain note saying so.
 * With [refs] (a source chip from the route) it opens on the cited page with the quote marked.
 */
@Composable
internal fun DocumentViewer(title: String, chunks: List<SourceChunk>, pdf: SourceDocument?, refs: List<SourceRef> = emptyList(), attach: SourceAttach? = null, onDismiss: () -> Unit) {
    val attached = ((attach?.flow?.state as? AttachState.Done)?.result as? AttachResult.Accepted)?.document
    val doc = pdf ?: attached
    // Offered only while there is no PDF, and kept after an attempt so its outcome stays readable.
    val offer = attach?.takeIf { pdf == null }
    if (doc == null) TextDocument(title, chunks, refs, offer, failed = false, onDismiss)
    else PdfDocument(title, chunks, doc, refs, onText = { TextDocument(title, chunks, refs, null, failed = true, onDismiss) }, onDismiss)
}

/** The viewer's frame while the file is being looked up, so a tap answers at once. */
@Composable
internal fun DocumentLoading(title: String, onDismiss: () -> Unit) {
    val pager = rememberPagerState { 0 }
    val loading = stringResource(R.string.source_pdf_loading)
    SourceFrame("", 0, pager, onDismiss, title = title, legend = false) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("document_loading"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = Color.White)
        }
    }
}

@Composable
private fun PdfDocument(title: String, chunks: List<SourceChunk>, pdf: SourceDocument, refs: List<SourceRef>, onText: @Composable () -> Unit, onDismiss: () -> Unit) {
    val open = LocalPageImages.current
    val opened by produceState<Opened>(Opened.Loading, pdf.file) {
        val pages = try { open(pdf.file) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (pages == null || pages.pageCount != pdf.pageCount) { pages?.close(); value = Opened.Failed; return@produceState }
        value = Opened.Ready(pages)
        awaitDispose { pages.close() }
    }
    if (opened == Opened.Failed) return onText()
    val pages = (opened as? Opened.Ready)?.pages
    val marks = remember(chunks, refs, pdf) { if (refs.isEmpty()) emptyMap() else pdfMarks(chunks, refs, pdf.layout) }
    val boxes = remember(pdf) { pdf.layout.pages.associateBy { it.pageIndex } }
    val cited = remember(chunks, refs) { refs.firstNotNullOfOrNull { ref -> chunks.firstOrNull { it.chunkId == ref.chunkId }?.pageIndex } }
    val start = (marks.entries.firstOrNull { it.value.rects.isNotEmpty() }?.key ?: cited ?: 0).coerceIn(0, (pdf.pageCount - 1).coerceAtLeast(0))
    val pager = rememberPagerState(initialPage = start) { pdf.pageCount }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    SourceFrame("", pdf.pageCount, pager, onDismiss, title = title, legend = marks.values.any { it.rects.isNotEmpty() }) {
        if (pages == null) {
            val loading = stringResource(R.string.source_pdf_loading)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = Color.White) }
        } else HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed) { i ->
            PdfPage(pages, i, (i + 1).toString(), pdf.pageCount, boxes[i], marks[i], current = i == pager.currentPage, onZoom = { zoomed = it },
                modifier = Modifier.fillMaxSize(), note = marks.isNotEmpty())
        }
    }
}

@Composable
private fun TextDocument(title: String, chunks: List<SourceChunk>, refs: List<SourceRef>, offer: SourceAttach?, failed: Boolean, onDismiss: () -> Unit) {
    val pages = remember(chunks) {
        chunks.groupBy { it.pageIndex }.toSortedMap().map { (i, cs) -> SourcePage(i, cs.first().pageLabel ?: (i + 1).toString(), cs) }
    }
    val cited = remember(refs) { refs.map { it.chunkId }.toSet() }
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { p -> p.chunks.any { it.chunkId in cited } }.coerceAtLeast(0)) { pages.size }
    SourceFrame("", pages.size, pager, onDismiss, title = title, legend = false) {
        // With the attach bar below, its note already says it; one honest sentence either way.
        if (offer == null || failed) Text(stringResource(if (failed) R.string.document_open_failed else R.string.document_text_note),
            Modifier.fillMaxWidth().background(Color(0xFF4A4E52)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("document_text_note"),
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
        if (pages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.document_no_text), color = Color.White, modifier = Modifier.testTag("document_no_text"))
        } else HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { i ->
            PageSheet(pages[i], refs, Modifier.fillMaxSize(), note = refs.isNotEmpty())
        }
        if (!failed) offer?.let { AttachBar(it) }
    }
}
