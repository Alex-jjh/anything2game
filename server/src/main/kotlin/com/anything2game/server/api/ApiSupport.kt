package com.anything2game.server.api

import com.anything2game.contract.ApiError
import com.anything2game.contract.ContractJson
import com.anything2game.contract.ErrorBody
import com.anything2game.contract.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer

/** An error that is part of the API contract: status, stable code, message safe to show, retry hint. */
class ApiException(val status: HttpStatusCode, val code: String, message: String, val retryable: Boolean = false) : RuntimeException(message) {
    fun body() = ErrorBody(ApiError(code, message ?: code, retryable))

    companion object {
        fun notFound(what: String = "Not found") = ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, what)
        fun badRequest(message: String) = ApiException(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, message)
        fun tooLarge(message: String) = ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.FILE_TOO_LARGE, message)
        fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Missing or invalid install token.")
        fun quota(message: String) = ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.QUOTA_EXCEEDED, message, retryable = false)
    }
}

/** Reads at most [max] bytes of the body. A longer body fails with 413 without being read to the end. */
@Suppress("DEPRECATION") // readRemaining(limit) is the bounded read; the suggested readBuffer has different limit semantics
suspend fun ApplicationCall.readLimitedBody(max: Int): ByteArray {
    val declared = request.contentLength()
    if (declared != null && declared > max) throw ApiException.tooLarge("The request body is larger than $max bytes.")
    val bytes = receiveChannel().readRemaining(max.toLong() + 1).readByteArray()
    if (bytes.size > max) throw ApiException.tooLarge("The request body is larger than $max bytes.")
    return bytes
}

suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>, max: Int): T {
    val text = readLimitedBody(max).toString(Charsets.UTF_8)
    return try {
        ContractJson.json.decodeFromString(serializer, text.ifBlank { "{}" })
    } catch (e: SerializationException) {
        throw ApiException.badRequest("The request body is not valid for this endpoint.")
    } catch (e: IllegalArgumentException) {
        throw ApiException.badRequest("The request body is not valid for this endpoint.")
    }
}
