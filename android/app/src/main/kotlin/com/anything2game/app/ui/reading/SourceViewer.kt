package com.anything2game.app.ui.reading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anything2game.app.R
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceLayout
import com.anything2game.contract.SourceRef
import com.anything2game.app.data.tutor.SourceDocument
import kotlinx.coroutines.launch

/** Marker yellow, the colour of a highlighter in a PDF reader; the page itself stays white whatever the skin. */
internal val Marker = Color(0xFFFFE066)
private val PageInk = Color(0xFF1F1F1F)

/** One page of the document: every chunk printed on it, in order. */
private data class SourcePage(val pageIndex: Int, val label: String, val chunks: List<SourceChunk>)

/**
 * "Show source" as a PDF reader would show it: the whole printed page, the quoted sentences marked in yellow, and the
 * neighbouring pages one swipe away. With the reader's own PDF and a quote placed on it ([pdf]), the real page is shown
 * ([PdfSourceViewer]); otherwise (every preset course, text imports, a file that will not open) the extracted text is.
 */
@Composable
internal fun SourceViewer(sources: List<SourceChunk>, refs: List<SourceRef>, pdf: SourceDocument? = null, onDismiss: () -> Unit) {
    val marks = remember(sources, refs, pdf) { pdf?.let { pdfMarks(sources, refs, it.layout) }?.takeIf { m -> m.values.any { it.rects.isNotEmpty() } } }
    if (pdf == null || marks == null) TextSourceViewer(sources, refs, onDismiss)
    else PdfSourceViewer(sources, pdf, marks, onText = { TextSourceViewer(sources, refs, onDismiss) }, onDismiss = onDismiss)
}

/** The extracted text laid out as pages: what "Show source" is whenever the original PDF is not on this phone. */
@Composable
private fun TextSourceViewer(sources: List<SourceChunk>, refs: List<SourceRef>, onDismiss: () -> Unit) {
    val pages = remember(sources) {
        sources.groupBy { it.pageIndex }.toSortedMap().map { (i, chunks) -> SourcePage(i, chunks.first().pageLabel ?: (i + 1).toString(), chunks) }
    }
    val cited = remember(refs) { refs.map { it.chunkId }.toSet() }
    val start = pages.indexOfFirst { p -> p.chunks.any { it.chunkId in cited } }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { pages.size }
    SourceFrame(pages.getOrNull(pager.currentPage)?.label.orEmpty(), pages.size, pager, onDismiss) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { i ->
            PageSheet(pages[i], refs, Modifier.fillMaxSize())
        }
    }
}

/** The reader's chrome around either kind of page: close, "p. X · N pages", and Prev/Next (48dp) beside the marker legend. */
@Composable
internal fun SourceFrame(label: String, count: Int, pager: PagerState, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("source_viewer"), color = Color(0xFF5F6368)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color(0xFF3C4043)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("source_close")) { Text(stringResource(R.string.tutor_close), color = Color.White) }
                    Text(stringResource(R.string.tutor_source_title), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.source_page_of, label, count),
                        style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(end = 12.dp).testTag("source_page_label"))
                }
                content()
                Row(Modifier.fillMaxWidth().background(Color(0xFF3C4043)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("◂ " + stringResource(R.string.source_prev_page), color = if (pager.currentPage > 0) Color.White else Color.White.copy(alpha = 0.4f))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 14.dp, height = 10.dp).background(Marker, RoundedCornerShape(2.dp)))
                        Text("  " + stringResource(R.string.source_marker_note), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                    }
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < count - 1, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.source_next_page) + " ▸", color = if (pager.currentPage < count - 1) Color.White else Color.White.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageSheet(page: SourcePage, refs: List<SourceRef>, modifier: Modifier) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Where the first highlight sits, so the page opens scrolled to it (as a PDF reader jumps to a search hit).
    var firstMark by remember(page.pageIndex) { mutableStateOf<Float?>(null) }
    val pagePadding = with(density) { (16.dp + 26.dp).toPx() }
    LaunchedEffect(firstMark) { firstMark?.let { scroll.animateScrollTo((it - with(density) { 96.dp.toPx() }).toInt().coerceAtLeast(0)) } }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), color = Color.White, shadowElevation = 6.dp, shape = RoundedCornerShape(2.dp), border = BorderStroke(0.5.dp, Color(0x22000000))) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                page.chunks.forEach { chunk ->
                    val quotes = refs.filter { it.chunkId == chunk.chunkId }.map { it.quote }
                    val marked = remember(chunk.text, quotes) { highlighted(chunk.text, quotes) }
                    var top by remember { mutableStateOf<Float?>(null) }
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    if (marked.first >= 0 && firstMark == null) { val t = top; val l = layout; if (t != null && l != null) firstMark = t + l.getBoundingBox(marked.first).top }
                    Text(
                        marked.text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 26.sp), color = PageInk,
                        modifier = Modifier.testTag(if (marked.first >= 0) "source_highlighted" else "source_text").onGloballyPositioned { top = it.positionInParent().y + pagePadding },
                        onTextLayout = { layout = it },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.evidence_page, page.label), style = MaterialTheme.typography.labelMedium, color = Color(0xFF757575), fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        Text(stringResource(R.string.tutor_source_note), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth())
    }
}

/** The chunk text with each quote marked; [first] is the start of the earliest mark, or -1 when no quote was found. */
private class Marked(val text: AnnotatedString, val first: Int)

private fun highlighted(text: String, quotes: List<String>): Marked {
    val ranges = quotes.mapNotNull { find(text, it) }.sortedBy { it.first }
    val annotated = buildAnnotatedString {
        var at = 0
        ranges.forEach { r ->
            if (r.first < at) return@forEach
            append(text.substring(at, r.first))
            withStyle(SpanStyle(background = Marker.copy(alpha = 0.85f))) { append(text.substring(r.first, r.last + 1)) }
            at = r.last + 1
        }
        append(text.substring(at))
    }
    return Marked(annotated, ranges.firstOrNull()?.first ?: -1)
}

/** A quote matches the chunk with whitespace normalised (the citation rule); the same match places it on the PDF page. */
internal fun find(text: String, quote: String): IntRange? = SourceLayout.find(text, quote)
