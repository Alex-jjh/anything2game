package com.anything2game.app.data.tutor

import android.content.Context
import com.anything2game.app.BuildConfig
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

typealias TutorApiException = com.anything2game.app.data.api.ApiException

class TutorHttpClient(private val context: Context, private val transport: com.anything2game.app.data.api.ApiTransport) {
    constructor(context: Context, baseUrl: String = BuildConfig.TUTOR_API, prefsName: String = "tutor_connection") :
        this(context, com.anything2game.app.data.api.ApiTransport(context, baseUrl, prefsName))
    val configured get() = transport.configured
    val local get() = transport.local
    val endpoint get() = transport.endpoint
    suspend fun request(method: String, path: String, body: String? = null) = transport.request(method, path, body)

    suspend fun upload(request: ImportRequest): DocumentResponse {
        val text = when (request) {
            is ImportRequest.SharedText -> request("POST", "/v1/documents", ContractJson.json.encodeToString(TextDocumentRequest.serializer(),
                TextDocumentRequest(DocumentKind.TEXT, request.title, listOf(PageText(0, request.text, "1")))))
            is ImportRequest.PdfFile -> return uploadPdf(request.title, readPdf(request), layout = false)
        }
        return ContractJson.json.decodeFromString(DocumentResponse.serializer(), text)
    }

    /** The picked PDF's bytes, read once (the content:// grant is temporary) and bounded like the server's limit. */
    suspend fun readPdf(request: ImportRequest.PdfFile): ByteArray = withContext(Dispatchers.IO) {
        if ((request.sizeBytes ?: 0L) > MAX_PDF) throw TutorApiException("FILE_TOO_LARGE", false)
        context.contentResolver.openInputStream(request.uri)?.use { stream ->
            val data = readBounded(stream, MAX_PDF + 1)
            if (data.size > MAX_PDF) throw TutorApiException("FILE_TOO_LARGE", false)
            data
        } ?: throw TutorApiException("FILE_UNAVAILABLE", false)
    }

    /** [layout]: also ask for the text geometry ("Show source" on the real page); that answer may be a few MB, hence its own cap. */
    suspend fun uploadPdf(title: String, bytes: ByteArray, layout: Boolean): DocumentResponse {
        val boundary = "a2g-" + UUID.randomUUID()
        val clean = title.replace(Regex("[\\r\\n]"), " ").take(200)
        // Fixed filename avoids injecting untrusted metadata into multipart headers.
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n$clean\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"
        val text = transport.post(if (layout) "/v1/documents?layout=1" else "/v1/documents", "multipart/form-data; boundary=$boundary",
            prefix.toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray(), if (layout) MAX_LAYOUT_RESPONSE else MAX_RESPONSE)
        // With a layout this is MBs of JSON; the caller is usually a viewModelScope (Main).
        return withContext(Dispatchers.Default) { ContractJson.json.decodeFromString(DocumentResponse.serializer(), text) }
    }

    private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray = PdfBytes.readBounded(stream, limit)
    companion object {
        private const val MAX_PDF = PdfBytes.MAX
        private const val MAX_RESPONSE = 2 * 1024 * 1024
        private const val MAX_LAYOUT_RESPONSE = 12 * 1024 * 1024
    }
}
