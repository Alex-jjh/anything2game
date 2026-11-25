package com.anything2game.app.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceLayout
import com.anything2game.contract.SourceRef

/** Marker yellow, the colour of a highlighter in a PDF reader; the same on a real page and on the text. */
internal val Marker = Color(0xFFFFE066)
private val MarkedInk = Color(0xFF1F1F1F)

/** One page of the document: every chunk printed on it, in order. */
internal data class SourcePage(val pageIndex: Int, val label: String, val chunks: List<SourceChunk>)

internal fun sourcePages(chunks: List<SourceChunk>): List<SourcePage> =
    chunks.groupBy { it.pageIndex }.toSortedMap().map { (i, cs) -> SourcePage(i, cs.first().displayPage(), cs) }

/**
 * The text taken from the file, set as a reading layout: a heading per page ("Page 3"), the paragraphs under it on the
 * app's own paper, and the cited passage marked inline. Deliberately not a sheet of paper: the real file, when the phone
 * has it, is drawn by [OriginalPdfRenderer]; this must never be mistaken for it.
 */
internal class ExtractedTextRenderer(chunks: List<SourceChunk>, private val refs: List<SourceRef>, startPage: Int?) : SourceRenderer {
    private val pages = sourcePages(chunks)
    private val quoted = refs.map { it.chunkId }.toSet()

    override val pageCount get() = pages.size
    override val startPage = (startPage?.let { at -> pages.indexOfFirst { it.pageIndex == at } }?.takeIf { it >= 0 }
        ?: pages.indexOfFirst { p -> p.chunks.any { it.chunkId in quoted } }).coerceAtLeast(0)
    override val legend = pages.any { p -> p.chunks.any { c -> refs.any { it.chunkId == c.chunkId && SourceLayout.find(c.text, it.quote) != null } } }

    @Composable override fun Page(index: Int, current: Boolean, modifier: Modifier) = TextPage(pages[index], refs, current, modifier)
}

@Composable
private fun TextPage(page: SourcePage, refs: List<SourceRef>, current: Boolean, modifier: Modifier) {
    val skin = A2g.skin
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Where the first mark sits, so the page opens scrolled to it (as a reader jumps to a search hit).
    var firstMark by remember(page.pageIndex) { mutableStateOf<Float?>(null) }
    LaunchedEffect(firstMark) { firstMark?.let { scroll.animateScrollTo((it - with(density) { 96.dp.toPx() }).toInt().coerceAtLeast(0)) } }
    // The heading stays in view while the passages scroll under it, so the reader always knows which page this is.
    Column(modifier.background(skin.paper)) {
    Text(stringResource(R.string.evidence_page, page.label), style = MaterialTheme.typography.titleMedium, color = skin.evidence, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(skin.card).padding(horizontal = 20.dp, vertical = 10.dp).testTag(if (current) "source_text_heading" else "source_text_heading_beside"))
    Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        page.chunks.forEach { chunk ->
            val quotes = refs.filter { it.chunkId == chunk.chunkId }.map { it.quote }
            val marked = remember(chunk.text, quotes) { marked(chunk.text, quotes) }
            var top by remember { mutableStateOf<Float?>(null) }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            if (marked.first >= 0 && firstMark == null) { val t = top; val l = layout; if (t != null && l != null) firstMark = t + l.getBoundingBox(marked.first).top }
            Text(marked.text, style = MaterialTheme.typography.bodyLarge, color = skin.ink,
                modifier = Modifier.fillMaxWidth().testTag(if (marked.first >= 0) "source_highlighted" else "source_text").onGloballyPositioned { top = it.positionInParent().y },
                onTextLayout = { layout = it })
        }
        if (refs.isNotEmpty()) Text(stringResource(R.string.tutor_source_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
    }
    }
}

/** The chunk text as shown, with each quote marked; [first] is where the earliest mark starts in [text], or -1 when no quote was found. */
internal class Marked(val text: AnnotatedString, val first: Int)

/** A wiki-style heading on a line of its own, "== Research and use ==": the same number of marks on both sides. */
private val HeadingLine = Regex("^(={2,6})(?!=)[ \\t]*([^=\\s].*?)[ \\t]*(?<!=)\\1$")
private val HeadingStyle = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.2.em)

/**
 * The chunk text with each quote marked, and each wiki-style heading line shown as a heading: its words, in bold and larger, with
 * the "==" marks left out. Quotes are found in the text as the file gave it, then placed in the text as shown.
 */
internal fun marked(text: String, quotes: List<String>): Marked {
    val found = quotes.mapNotNull { SourceLayout.find(text, it) }.sortedBy { it.first }
    // Which characters are the marks around a heading (not shown), and which are its words (shown large).
    val hidden = BooleanArray(text.length)
    val words = mutableListOf<IntRange>()
    var lineStart = 0
    for (line in text.split('\n')) {
        HeadingLine.matchEntire(line.trim())?.let { heading ->
            val label = heading.groupValues[2]
            val at = lineStart + line.indexOf(label, line.indexOf(heading.groupValues[1]) + heading.groupValues[1].length)
            for (i in lineStart until at) hidden[i] = true
            for (i in at + label.length until lineStart + line.length) hidden[i] = true
            words += at until at + label.length
        }
        lineStart += line.length + 1
    }
    // Where each original character lands in the shown text.
    val shown = IntArray(text.length + 1)
    val visible = StringBuilder()
    for (i in text.indices) { shown[i] = visible.length; if (!hidden[i]) visible.append(text[i]) }
    shown[text.length] = visible.length
    var taken = 0
    val ranges = found.filter { r -> (r.first >= taken).also { if (it) taken = r.last + 1 } }
    val annotated = buildAnnotatedString {
        append(visible.toString())
        words.forEach { addStyle(HeadingStyle, shown[it.first], shown[it.last + 1]) }
        ranges.forEach { addStyle(SpanStyle(background = Marker, color = MarkedInk), shown[it.first], shown[it.last + 1]) }
    }
    return Marked(annotated, ranges.firstOrNull()?.let { shown[it.first] } ?: -1)
}
