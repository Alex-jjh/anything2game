package com.anything2game.app.data.tutor

import android.content.Context
import com.anything2game.app.BuildConfig
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

class TutorApiException(val code: String, val retryable: Boolean) : Exception(code)

/** No AI SDK/keys: this adapter talks only to our own API. Debug defaults to an adb-reversed loopback server. */
class TutorHttpClient(private val context: Context, private val baseUrl: String = BuildConfig.TUTOR_API, prefsName: String = "tutor_connection") {
    // One install token per local profile: the server sees each profile as a separate owner.
    private val auth = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val tokenMutex = Mutex()
    val configured: Boolean get() = baseUrl.isNotBlank()
    val local: Boolean get() = configured && URI(baseUrl).host in setOf("127.0.0.1", "localhost")
    val endpoint: String get() = baseUrl
    private fun validateUrl() {
        if (!configured) throw TutorApiException("NOT_CONFIGURED", false)
        val u = URI(baseUrl)
        require(u.userInfo == null && u.query == null && u.fragment == null)
        require(u.scheme == "https" || (BuildConfig.DEBUG && u.scheme == "http" && local))
    }
    private suspend fun token(): String = tokenMutex.withLock {
        validateUrl()
        // Tokens are scoped to the endpoint; changing a build's endpoint must not send an old token there.
        auth.getString(baseUrl, null)?.let { return@withLock it }
        val text = raw("POST", "/v1/installs", "application/json", "{}".toByteArray(), null)
        ContractJson.json.decodeFromString(InstallResponse.serializer(), text).token.also { token ->
            check(auth.edit().putString(baseUrl, token).commit()) { "Cannot save install token" }
        }
    }
    suspend fun request(method: String, path: String, body: String? = null): String =
        raw(method, path, "application/json", body?.toByteArray(), token())

    suspend fun upload(request: ImportRequest): DocumentResponse {
        val text = when (request) {
            is ImportRequest.SharedText -> request("POST", "/v1/documents", ContractJson.json.encodeToString(TextDocumentRequest.serializer(),
                TextDocumentRequest(DocumentKind.TEXT, request.title, listOf(PageText(0, request.text, "1")))))
            is ImportRequest.PdfFile -> {
                val bytes = withContext(Dispatchers.IO) {
                    if ((request.sizeBytes ?: 0L) > MAX_PDF) throw TutorApiException("FILE_TOO_LARGE", false)
                    context.contentResolver.openInputStream(request.uri)?.use { stream ->
                        val data = readBounded(stream, MAX_PDF + 1)
                        if (data.size > MAX_PDF) throw TutorApiException("FILE_TOO_LARGE", false)
                        data
                    } ?: throw TutorApiException("FILE_UNAVAILABLE", false)
                }
                val boundary = "a2g-" + UUID.randomUUID()
                val title = request.title.replace(Regex("[\\r\\n]"), " ").take(200)
                // Fixed filename avoids injecting untrusted metadata into multipart headers.
                val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n$title\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"
                raw("POST", "/v1/documents", "multipart/form-data; boundary=$boundary", prefix.toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray(), token())
            }
        }
        return ContractJson.json.decodeFromString(DocumentResponse.serializer(), text)
    }

    private suspend fun raw(method: String, path: String, type: String, body: ByteArray?, token: String?): String = withContext(Dispatchers.IO) {
        validateUrl()
        val connection = URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 90000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val data = stream?.use { readBounded(it, 2 * 1024 * 1024 + 1) } ?: byteArrayOf()
            if (data.size > 2 * 1024 * 1024) throw TutorApiException("RESPONSE_TOO_LARGE", false)
            val result = data.toString(Charsets.UTF_8)
            if (status !in 200..299) {
                val error = runCatching { ContractJson.json.decodeFromString(ErrorBody.serializer(), result).error }.getOrNull()
                throw TutorApiException(error?.code ?: "HTTP_$status", error?.retryable ?: (status >= 500))
            }
            result
        } finally { connection.disconnect() }
    }
    private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (n < 0) break
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    companion object { private const val MAX_PDF = 20 * 1024 * 1024 }
}
