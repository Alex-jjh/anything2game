package com.anything2game.app.ui.reading

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.NormRect
import com.anything2game.contract.PageBox
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceLayout
import com.anything2game.contract.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rendered pages of one PDF. A seam: the platform renderer on a phone, a fake under Robolectric (which cannot render). */
interface PageImages : Closeable {
    val pageCount: Int
    /** Page [index] (0-based, physical) about [widthPx] wide, white behind the ink. */
    suspend fun render(index: Int, widthPx: Int): ImageBitmap
}

/** How "Show source" opens a kept PDF; tests provide fakes. Throwing means "show the text instead". */
internal val LocalPageImages = staticCompositionLocalOf<suspend (File) -> PageImages> { { PdfRendererPages.open(it) } }

/** What one PDF page marks: the rectangles of the quotes placed on it, and every quote cited there (caption, TalkBack). */
internal class PageMarks(val rects: List<NormRect>, val quotes: List<Quote>) {
    /** [placed]: the geometry put this quote on the page; a caption must not claim a mark that is not drawn. */
    data class Quote(val text: String, val placed: Boolean)
}

/** Marks per physical page of every cited chunk; a quote the geometry could not place still gets its caption. */
internal fun pdfMarks(sources: List<SourceChunk>, refs: List<SourceRef>, layout: DocumentLayout): Map<Int, PageMarks> {
    val chunks = sources.associateBy { it.chunkId }
    val layouts = layout.chunks.associateBy { it.chunkId }
    val rects = linkedMapOf<Int, MutableList<NormRect>>()
    val quotes = linkedMapOf<Int, LinkedHashMap<String, Boolean>>()
    for (ref in refs) {
        val chunk = chunks[ref.chunkId] ?: continue
        val r = SourceLayout.rects(chunk, layouts[ref.chunkId], ref.quote)
        rects.getOrPut(chunk.pageIndex) { mutableListOf() } += r
        quotes.getOrPut(chunk.pageIndex) { linkedMapOf() }.merge(ref.quote, r.isNotEmpty(), Boolean::or)
    }
    return rects.mapValues { (page, r) -> PageMarks(r, quotes[page].orEmpty().map { (q, placed) -> PageMarks.Quote(q, placed) }) }
}

private sealed interface Opened {
    data object Loading : Opened
    data object Failed : Opened
    class Ready(val pages: PageImages) : Opened
}

/**
 * The real PDF page with the quote marked where it is printed, like a PDF reader's highlight. Never half-shown: a file
 * that will not open, or has another page count than the server read, gives the text viewer ([onText]) instead.
 */
@Composable
internal fun PdfSourceViewer(sources: List<SourceChunk>, pdf: SourceDocument, marks: Map<Int, PageMarks>, onText: @Composable () -> Unit, onDismiss: () -> Unit) {
    val open = LocalPageImages.current
    val opened by produceState<Opened>(Opened.Loading, pdf.file) {
        val pages = try { open(pdf.file) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (pages == null || pages.pageCount != pdf.pageCount) { pages?.close(); value = Opened.Failed; return@produceState }
        value = Opened.Ready(pages)
        awaitDispose { pages.close() }
    }
    if (opened == Opened.Failed) return onText()
    val pages = (opened as? Opened.Ready)?.pages
    val labels = remember(sources) { sources.associate { it.pageIndex to (it.pageLabel ?: (it.pageIndex + 1).toString()) } }
    val label = { i: Int -> labels[i] ?: (i + 1).toString() }
    val boxes = remember(pdf) { pdf.layout.pages.associateBy { it.pageIndex } }
    val start = marks.entries.firstOrNull { it.value.rects.isNotEmpty() }?.key ?: 0
    val pager = rememberPagerState(initialPage = start.coerceIn(0, pdf.pageCount - 1)) { pdf.pageCount }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    SourceFrame(label(pager.currentPage), pdf.pageCount, pager, onDismiss) {
        if (pages == null) {
            val loading = stringResource(R.string.source_pdf_loading)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = Color.White) }
        } else HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed) { i ->
            PdfPage(pages, i, label(i), pdf.pageCount, boxes[i], marks[i], current = i == pager.currentPage, onZoom = { zoomed = it }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PdfPage(pages: PageImages, index: Int, label: String, total: Int, box: PageBox?, marks: PageMarks?, current: Boolean, onZoom: (Boolean) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom by rememberUpdatedState(onZoom)
    // Rendered at the shown width, and again at twice that once zoomed in, so text stays sharp without holding big bitmaps.
    val target = (widthPx * if (scale > 1.5f) 2 else 1).coerceAtMost(2400)
    val image by produceState<ImageBitmap?>(null, pages, index, target) {
        if (target > 0) try { value = pages.render(index, target) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
    val aspect = image?.let { it.width / it.height.toFloat() } ?: box?.let { it.width / it.height } ?: (8.5f / 11f)
    // Geometry measured in a page of another shape belongs to another file: drawing it would mark the wrong words.
    val placed = box != null && abs(box.width / box.height - aspect) <= 0.03f * aspect
    val rects = if (placed) marks?.rects.orEmpty() else emptyList()
    LaunchedEffect(current) { if (!current) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(widthPx, rects) {
        val first = rects.minByOrNull { it.top } ?: return@LaunchedEffect
        if (widthPx > 0) scroll.animateScrollTo((with(density) { 14.dp.toPx() - 96.dp.toPx() } + first.top * widthPx / aspect).roundToInt().coerceAtLeast(0))
    }
    val description = stringResource(R.string.source_pdf_page_desc, label, total)
    Column(modifier.verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().aspectRatio(aspect).onSizeChanged { widthPx = it.width }.clipToBounds().background(Color.White)
            .pointerInput(Unit) {
                // Pinch zooms (1..4x) and, once zoomed, one finger pans; at 1x single-finger drags go to the pager and the scroll.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val maxX = size.width * (scale - 1f) / 2f; val maxY = size.height * (scale - 1f) / 2f
                            val moved = offset + event.calculatePan()
                            offset = if (scale == 1f) Offset.Zero else Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                            zoom(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero; zoom(false) }) }) {
            BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
                val img = image
                if (img == null) CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = description }, color = Color(0xFF9AA0A6))
                else Image(img, contentDescription = description, modifier = Modifier.fillMaxSize().testTag("source_pdf_page"), contentScale = ContentScale.FillBounds)
                // Translucent marker multiplied into the ink, so the words stay readable through it as in a PDF reader.
                rects.forEach { r ->
                    Box(Modifier.offset(x = maxWidth * r.left, y = maxHeight * r.top).size(width = maxWidth * (r.right - r.left), height = maxHeight * (r.bottom - r.top))
                        .drawBehind { drawRect(Marker, alpha = 0.5f, blendMode = BlendMode.Multiply) }.testTag("source_highlight_rect"))
                }
            }
        }
        // The quote as text: TalkBack reads it, and it stays visible if the marks could not be placed on this page.
        marks?.quotes?.forEach { quote ->
            val marked = placed && quote.placed
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(6.dp), border = BorderStroke(0.5.dp, Color(0x22000000))) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    // The marker swatch only next to a quote that is marked on the page above.
                    Box(Modifier.padding(top = 5.dp).size(width = 14.dp, height = 10.dp).background(if (marked) Marker else Color(0x22000000), RoundedCornerShape(2.dp)))
                    Text(stringResource(if (marked) R.string.source_pdf_quote else R.string.source_pdf_quote_unplaced, quote.text), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1F1F1F),
                        modifier = Modifier.padding(start = 10.dp).testTag("source_quote"))
                }
            }
        }
        Text(stringResource(R.string.source_pdf_zoom_hint), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth())
        Text(stringResource(R.string.tutor_source_note), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth())
    }
}

/**
 * [PageImages] over the platform's PdfRenderer (no third-party PDF code). It allows one open page at a time, hence the lock.
 * [close] runs on the main thread (dispose) and must not wait out a slow render: it only asks, and whoever holds the lock
 * last closes the renderer.
 */
internal class PdfRendererPages private constructor(private val renderer: PdfRenderer) : PageImages {
    private val lock = ReentrantLock()
    @Volatile private var closing = false
    private var closed = false // guarded by lock
    override val pageCount: Int = renderer.pageCount

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap = withContext(Dispatchers.IO) {
        try { lock.withLock { renderLocked(index, widthPx) } } finally { closeIfAsked() }
    }

    private fun renderLocked(index: Int, widthPx: Int): ImageBitmap {
        check(!closing && !closed) { "Renderer closed" }
        val page = renderer.openPage(index)
        try {
            var w = widthPx.coerceIn(1, MAX_SIDE)
            var h = (w * page.height.toFloat() / page.width).roundToInt().coerceIn(1, MAX_SIDE)
            // A very tall page at high zoom would need hundreds of MB; keep a bitmap under ~8 Mpx.
            val shrink = sqrt(MAX_PIXELS / (w.toFloat() * h)).coerceAtMost(1f)
            if (shrink < 1f) { w = (w * shrink).roundToInt().coerceAtLeast(1); h = (h * shrink).roundToInt().coerceAtLeast(1) }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap.asImageBitmap()
        } finally { page.close() }
    }

    /** Never blocks: if a render holds the lock, that render closes the renderer when it lets go ([render]'s finally). */
    override fun close() { closing = true; closeIfAsked() }

    private fun closeIfAsked() {
        if (closing && lock.tryLock()) try { if (!closed) { closed = true; renderer.close() } } finally { lock.unlock() }
    }

    companion object {
        private const val MAX_SIDE = 4096
        private const val MAX_PIXELS = 8_000_000f

        /** Throws for a missing, damaged or encrypted file; the renderer owns (and closes) the descriptor once it exists. */
        suspend fun open(file: File): PageImages = withContext(Dispatchers.IO) {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try { PdfRendererPages(PdfRenderer(fd)) } catch (e: Exception) { fd.close(); throw e }
        }
    }
}
