package com.anything2game.server.api

import com.anything2game.contract.ContractJson
import com.anything2game.contract.ErrorBody
import com.anything2game.contract.ApiError
import com.anything2game.contract.ErrorCodes
import com.anything2game.server.AppContext
import com.anything2game.server.compile.GenerationException
import com.anything2game.server.ingest.IngestException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** The Ktor application: JSON, error mapping, the global body-size gate and the routes. */
fun Application.module(ctx: AppContext) {
    val log = LoggerFactory.getLogger("com.anything2game.server.api")
    install(ContentNegotiation) { json(ContractJson.json) }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            if (e.status == HttpStatusCode.Unauthorized) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respond(e.status, e.body())
        }
        exception<IngestException> { call, e ->
            val status = when (e.code) {
                ErrorCodes.FILE_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
                ErrorCodes.UNSUPPORTED_TYPE -> HttpStatusCode.UnsupportedMediaType
                else -> HttpStatusCode.UnprocessableEntity
            }
            call.respond(status, ErrorBody(ApiError(e.code, e.message ?: e.code, retryable = false)))
        }
        exception<GenerationException> { call, e ->
            val status = when (e.code) {
                ErrorCodes.MODEL_TIMEOUT -> HttpStatusCode.GatewayTimeout
                ErrorCodes.NOT_FOUND -> HttpStatusCode.NotFound
                else -> HttpStatusCode.BadGateway
            }
            call.respond(status, ErrorBody(ApiError(e.code, e.message ?: e.code, e.retryable)))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ApiError(ErrorCodes.BAD_REQUEST, "The request could not be read.", false)))
        }
        exception<Throwable> { call, e ->
            if (e is CancellationException) throw e
            // Class name only: messages can contain request data.
            log.error("unhandled {} on {}", e.javaClass.name, call.request.local.uri.substringBefore('?'))
            call.respond(HttpStatusCode.InternalServerError, ErrorBody(ApiError(INTERNAL_ERROR, "Something went wrong on the server.", retryable = true)))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorBody(ApiError(ErrorCodes.NOT_FOUND, "Not found.", false)))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respond(HttpStatusCode.MethodNotAllowed, ErrorBody(ApiError(ErrorCodes.BAD_REQUEST, "Method not allowed.", false)))
        }
    }
    // Declared size gate for every route; routes that read bodies also enforce their own cap while reading.
    intercept(ApplicationCallPipeline.Plugins) {
        val declared = call.request.contentLength()
        if (declared != null && declared > ctx.config.limits.maxRequestBytes) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorBody(ApiError(ErrorCodes.FILE_TOO_LARGE, "The request is larger than ${ctx.config.limits.maxRequestBytes} bytes.", false)),
            )
            finish()
        }
    }
    routing { apiRoutes(ctx) }
    monitor.subscribe(ApplicationStopped) { ctx.close() }
}

/** Not in the contract's ErrorCodes (contract gap, see server/README.md). */
const val INTERNAL_ERROR = "INTERNAL_ERROR"
