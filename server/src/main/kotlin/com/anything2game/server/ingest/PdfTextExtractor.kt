package com.anything2game.server.ingest

import com.anything2game.contract.PageText
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException

/** Why a document was refused. [code] is one of the contract's ErrorCodes. */
class IngestException(val code: String, message: String) : RuntimeException(message)

class PdfTextExtractor(private val maxPages: Int) {

    /**
     * Text per page, page order, `pageIndex` 0-based. Pages without text stay in the list (empty text) so page numbers
     * are preserved; the caller decides what to do with them.
     */
    fun extract(bytes: ByteArray): List<PageText> {
        val doc = try {
            Loader.loadPDF(bytes)
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
