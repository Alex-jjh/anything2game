package com.anything2game.app.data.api

import android.content.Context
import com.anything2game.app.BuildConfig
import com.anything2game.app.data.tutor.PdfBytes
import com.anything2game.contract.*
import kotlinx.coroutines.Dispatchers
import com.anything2game.app.data.mutex
import java.io.File
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

class ApiException(val code: String, val retryable: Boolean, val detail: String? = null, val httpStatus: Int? = null) : Exception(detail ?: code) {
    val definiteRejection get() = httpStatus != null && httpStatus in 400..499 && httpStatus != 408
    fun error() = ApiError(code, detail ?: code, retryable)
}

class ApiTransport(context: Context, private val baseUrl: String = BuildConfig.TUTOR_API, prefsName: String = "tutor_connection",
    private val openConnection: (java.net.URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    // One install token per local profile: the server sees each profile as a separate owner.
    private val auth = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val tokenMutex = File(context.filesDir, "install-token-$prefsName").mutex()
    val configured: Boolean get() = baseUrl.isNotBlank()
    val local: Boolean get() = configured && URI(baseUrl).host in setOf("127.0.0.1", "localhost")
    val endpoint: String get() = baseUrl
    private fun validateUrl() {
        if (!configured) throw ApiException("NOT_CONFIGURED", false)
        val u = URI(baseUrl)
        require(u.userInfo == null && u.query == null && u.fragment == null)
        require(u.scheme == "https" || (BuildConfig.DEBUG && u.scheme == "http" && local))
    }
    private suspend fun token(): String = tokenMutex.withLock {
        validateUrl()
        // Tokens are scoped to the endpoint; changing a build's endpoint must not send an old token there.
        auth.getString(baseUrl, null)?.let { return@withLock it }
        val text = raw("POST", "/v1/installs", "application/json", "{}".toByteArray(), null)
        ContractJson.json.decodeFromString(InstallResponse.serializer(), text.decodeToString()).token.also { token ->
            check(auth.edit().putString(baseUrl, token).commit()) { "Cannot save install token" }
        }
    }
    suspend fun request(method: String, path: String, body: String? = null): String =
        raw(method, path, "application/json", body?.toByteArray(), token()).decodeToString()


    suspend fun archive(path: String) = raw("GET", path, "application/json", null, token(), 40 shl 20)

    suspend fun post(path: String, type: String, bytes: ByteArray, limit: Int) =
        raw("POST", path, type, bytes, token(), limit).decodeToString()

    private suspend fun raw(method: String, path: String, type: String, body: ByteArray?, token: String?, limit: Int = MAX_RESPONSE): ByteArray = withContext(Dispatchers.IO) {
        validateUrl()
        val connection = openConnection(URI(baseUrl.trimEnd('/') + path).toURL())
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 90000
            connection.setRequestProperty("Accept", if (path.startsWith("/v1/content/")) "application/vnd.anything2game.pack+zip" else "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val data = stream?.use { PdfBytes.readBounded(it, limit + 1) } ?: byteArrayOf()
            if (data.size > limit) throw ApiException("RESPONSE_TOO_LARGE", false)
            if (status !in 200..299) {
                val error = runCatching { ContractJson.json.decodeFromString(ErrorBody.serializer(), data.decodeToString()).error }.getOrNull()
                throw ApiException(error?.code ?: "HTTP_$status", error?.retryable ?: (status >= 500), error?.message, status)
            }
            data
        } finally { connection.disconnect() }
    }
    companion object { const val MAX_RESPONSE = 2 * 1024 * 1024 }
}
