package com.anything2game.app.data.tutor

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** The phone's rules for a PDF the reader picks, shared by the import upload and attaching a preset course's PDF. */
internal object PdfBytes {
    /** The server's upload limit, and the cap on any PDF this app copies into its own storage. */
    const val MAX = 20 * 1024 * 1024

    /** At most [limit] bytes; callers read [MAX] + 1 to tell "at the cap" from "over it" without holding more. */
    fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (n < 0) break
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    /** %PDF- must be the first bytes, the server's rule (PdfTextExtractor.looksLikePdf); names and MIME types are not trusted. */
    fun looksLikePdf(bytes: ByteArray): Boolean = bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
