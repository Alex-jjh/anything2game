package com.anything2game.server.ingest

import com.anything2game.contract.PageText
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Why a document was refused. [code] is one of the contract's ErrorCodes. */
class IngestException(val code: String, message: String) : RuntimeException(message)

/**
 * Reads the text layer with PDFBox. Parsing is bounded in time ([timeoutMillis]): a hostile or pathological PDF is abandoned and
 * refused (`UNSUPPORTED_TYPE`, "took too long") instead of holding a thread for as long as PDFBox likes. The work runs on a small
 * pool of daemon threads; on timeout the document is closed from outside, which makes PDFBox stop at its next read.
 */
class PdfTextExtractor(
    private val maxPages: Int,
    private val timeoutMillis: Long = 20_000,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    /** Text per page, page order, `pageIndex` 0-based. Pages without text stay in the list so page numbers are preserved. */
    fun extract(bytes: ByteArray): List<PageText> {
        val deadline = nanoClock() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val opened = AtomicReference<PDDocument?>()
        val future = pool.submit(Callable { extractWithin(bytes, deadline, opened) })
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
        com.anything2game.contract.ErrorCodes.UNSUPPORTED_TYPE,
        "The PDF took too long to read (more than ${timeoutMillis / 1000} s). Try a simpler file or send the text.",
    )

    private fun extractWithin(bytes: ByteArray, deadline: Long, opened: AtomicReference<PDDocument?>): List<PageText> {
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
            val stripper = PDFTextStripper()
            return (1..total).map { page ->
                if (nanoClock() - deadline > 0) throw tooSlow()
                stripper.startPage = page
                stripper.endPage = page
                val text = try {
                    stripper.getText(d)
                } catch (e: IOException) {
                    throw IngestException(com.anything2game.contract.ErrorCodes.UNSUPPORTED_TYPE, "The PDF text could not be extracted.")
                }
                PageText(pageIndex = page - 1, text = cleanText(text), pageLabel = page.toString())
            }
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
