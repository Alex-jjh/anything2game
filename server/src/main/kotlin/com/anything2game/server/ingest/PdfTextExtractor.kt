package com.anything2game.server.ingest

import com.anything2game.contract.PageText
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.anything2game.contract.SourceLayout
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Pages plus, per page, the box of every non-whitespace character of its text in reading order. */
class PdfExtraction(val pages: List<PageText>, val glyphs: List<PageGlyphs>)

/**
 * One page's characters as they appear in [PageText.text], whitespace and control characters left out, with their
 * boxes in crop-box points from the top-left. [chars] is empty when the page's geometry is not trusted (rotated pages);
 * characters of non-horizontal text keep a `null` box.
 */
class PageGlyphs(val pageIndex: Int, val width: Float, val height: Float, val chars: String, val boxes: List<Box?>) {
    /** A `null` box: the character was written without a usable position (rare, or not upright); no highlight spans it. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)
}

/**
 * Records the box of every character PDFTextStripper writes, in step with its output: `text` (not the glyphs' unicode)
 * decides which characters exist, because PDFBox normalises words (ligatures, bidi) between the two.
 */
private class GlyphStripper : PDFTextStripper() {
    private val chars = StringBuilder()
    private val boxes = mutableListOf<PageGlyphs.Box?>()
    private var width = 1f
    private var height = 1f
    private var trusted = true

    fun begin(page: PDPage) {
        chars.clear(); boxes.clear()
        width = page.cropBox.width; height = page.cropBox.height
        // PDFBox's coordinates are unrotated while a renderer's page is turned: skip rather than mark the wrong place.
        trusted = page.rotation % 360 == 0 && width > 0f && height > 0f
    }

    fun end(pageIndex: Int) = PageGlyphs(pageIndex, width, height, if (trusted) chars.toString() else "", if (trusted) boxes.toList() else emptyList())

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (trusted) record(text, textPositions)
        super.writeString(text, textPositions)
    }

    private fun record(text: String, positions: List<TextPosition>) {
        val wanted = text.filter(::keep)
        if (wanted.isEmpty()) return
        val found = mutableListOf<PageGlyphs.Box?>()
        for (p in positions) {
            val u = p.unicode.orEmpty()
            // *DirAdj values are in the text-direction frame: for vertical or upside-down text they are not page
            // coordinates, so such characters get no box (the run breaks there) instead of a mark on unrelated words.
            if (p.dir != 0f) { u.forEach { c -> if (keep(c)) found += null }; continue }
            // yScale is the full text rendering matrix (Tf, Tm and CTM); fontSizeInPt drops the CTM and truncates to an int.
            val size = p.yScale.takeIf { it > 0f } ?: p.heightDir
            val left = p.xDirAdj; val w = p.widthDirAdj
            u.forEachIndexed { k, c ->
                if (keep(c)) found += PageGlyphs.Box(left + w * k / u.length, p.yDirAdj - size * 0.9f, left + w * (k + 1) / u.length, p.yDirAdj + size * 0.25f)
            }
        }
        chars.append(wanted)
        when {
            found.size == wanted.length -> boxes += found
            found.isEmpty() || found.any { it == null } -> repeat(wanted.length) { boxes += null }
            else -> {
                // A normalised word of another length: spread its characters evenly over the word's box.
                val placed = found.filterNotNull()
                val l = placed.minOf { it.left }; val r = placed.maxOf { it.right }
                val t = placed.minOf { it.top }; val b = placed.maxOf { it.bottom }
                repeat(wanted.length) { k -> boxes += PageGlyphs.Box(l + (r - l) * k / wanted.length, t, l + (r - l) * (k + 1) / wanted.length, b) }
            }
        }
    }

    private fun keep(c: Char) = !SourceLayout.isGap(c) && !c.isISOControl()
}

/** Why a document was refused. [code] is one of the contract's ErrorCodes. */
class IngestException(val code: String, message: String) : RuntimeException(message)

/**
 * Reads the text layer with PDFBox. Parsing is bounded in time ([timeoutMillis]): a hostile or pathological PDF is abandoned and
 * refused (`PDF_TIMEOUT`, 422) instead of holding a thread for as long as PDFBox likes. The work runs on a small
 * pool of daemon threads; on timeout the document is closed from outside, which makes PDFBox stop at its next read.
 */
class PdfTextExtractor(
    private val maxPages: Int,
    private val timeoutMillis: Long = 20_000,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    /** Text per page, page order, `pageIndex` 0-based. Pages without text stay in the list so page numbers are preserved. */
    fun extract(bytes: ByteArray): List<PageText> = read(bytes, glyphs = false).pages

    /** [extract] plus where each character sits, for [ChunkLayouts]. Same text, same limits; only uploads that ask pay for it. */
    fun extractWithGlyphs(bytes: ByteArray): PdfExtraction = read(bytes, glyphs = true)

    private fun read(bytes: ByteArray, glyphs: Boolean): PdfExtraction {
        val deadline = nanoClock() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val opened = AtomicReference<PDDocument?>()
        val future = pool.submit(Callable { extractWithin(bytes, deadline, opened, glyphs) })
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            runCatching { opened.get()?.close() }
            throw tooSlow()
        } catch (e: ExecutionException) {
            throw e.cause.let { if (it is RuntimeException) it else IngestException(com.anything2game.contract.ErrorCodes.UNSUPPORTED_TYPE, "The PDF could not be read.") }
        }
    }

    private fun tooSlow() = IngestException(
        com.anything2game.contract.ErrorCodes.PDF_TIMEOUT,
        "The PDF took too long to read (more than ${timeoutMillis / 1000} s). Try a simpler file or send the text.",
    )

    private fun extractWithin(bytes: ByteArray, deadline: Long, opened: AtomicReference<PDDocument?>, glyphs: Boolean): PdfExtraction {
        val doc = try {
            Loader.loadPDF(bytes).also { opened.set(it) }
        } catch (e: InvalidPasswordException) {
            throw IngestException(com.anything2game.contract.ErrorCodes.ENCRYPTED_PDF, "The PDF is password protected. Remove the password and upload again.")
        } catch (e: IOException) {
            throw IngestException(com.anything2game.contract.ErrorCodes.UNSUPPORTED_TYPE, "The file could not be read as a PDF.")
        }
        doc.use { d ->
            if (d.isEncrypted) {
                throw IngestException(com.anything2game.contract.ErrorCodes.ENCRYPTED_PDF, "The PDF is encrypted. Remove the protection and upload again.")
            }
            val total = d.numberOfPages
            if (total > maxPages) {
                throw IngestException(com.anything2game.contract.ErrorCodes.FILE_TOO_LARGE, "The PDF has $total pages; the limit is $maxPages.")
            }
            val stripper = if (glyphs) GlyphStripper() else PDFTextStripper()
            val boxes = mutableListOf<PageGlyphs>()
            val pages = (1..total).map { page ->
                if (nanoClock() - deadline > 0) throw tooSlow()
                stripper.startPage = page
                stripper.endPage = page
                (stripper as? GlyphStripper)?.begin(d.getPage(page - 1))
                val text = try {
                    stripper.getText(d)
                } catch (e: IOException) {
                    throw IngestException(com.anything2game.contract.ErrorCodes.UNSUPPORTED_TYPE, "The PDF text could not be extracted.")
                }
                (stripper as? GlyphStripper)?.let { boxes += it.end(page - 1) }
                PageText(pageIndex = page - 1, text = cleanText(text), pageLabel = page.toString())
            }
            return PdfExtraction(pages, boxes)
        }
    }

    companion object {
        private val threadNumber = AtomicInteger()
        private val pool = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors())) { r ->
            Thread(r, "pdf-parse-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        }

        /** %PDF- must be the first bytes; the file name and the declared content type are never trusted. */
        fun looksLikePdf(bytes: ByteArray): Boolean =
            bytes.size >= 5 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() && bytes[4] == '-'.code.toByte()

        /** Drops NULs and other control characters; keeps line breaks and tabs. */
        fun cleanText(text: String): String = buildString(text.length) {
            for (ch in text) if (ch == '\n' || ch == '\t' || ch == '\r' || !ch.isISOControl()) append(ch)
        }.trim()
    }
}
