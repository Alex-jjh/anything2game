package com.anything2game.server.api

import com.anything2game.contract.tutor.*
import com.anything2game.contract.AskRequest
import com.anything2game.contract.ApiError
import com.anything2game.contract.CompileJobRequest
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.DocumentKind
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.DocumentResponse
import com.anything2game.contract.ErrorBody
import com.anything2game.contract.ErrorCodes
import com.anything2game.contract.FrameId
import com.anything2game.contract.InstallResponse
import com.anything2game.contract.PageText
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.TextDocumentRequest
import com.anything2game.server.AppContext
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.ingest.Chunker
import com.anything2game.server.ingest.IngestException
import com.anything2game.server.ingest.ChunkLayouts
import com.anything2game.server.ingest.PdfTextExtractor
import com.anything2game.server.store.JobRecord
import com.anything2game.server.store.StoredDocument
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class HealthResponse(val status: String, val contract: String)

private val random = SecureRandom()
private fun newId(prefix: String) = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also(random::nextBytes))
private val idShape = Regex("^[A-Za-z0-9_-]{1,80}$")

internal fun ApplicationCall.pathId(name: String): String =
    parameters[name]?.takeIf { idShape.matches(it) } ?: throw ApiException.notFound()

private fun parseFrame(raw: String?): FrameId =
    FrameId.entries.firstOrNull { ServerConfig.frameName(it) == raw } ?: throw ApiException.badRequest("Unknown frame '${raw?.take(40)}'.")

fun Route.apiRoutes(ctx: AppContext) {
    val log = LoggerFactory.getLogger("com.anything2game.server.api")
    val limits = ctx.config.limits
    val store = ctx.store

    // ownership: a resource of another owner is indistinguishable from one that does not exist
    fun ownedDocument(call: ApplicationCall): StoredDocument =
        store.getDocument(call.pathId("documentId"))?.takeIf { it.ownerId == call.principal.ownerId } ?: throw ApiException.notFound()
    fun ownedJob(call: ApplicationCall): JobRecord =
        store.getJob(call.pathId("jobId"))?.takeIf { it.ownerId == call.principal.ownerId && it.generation == null } ?: throw ApiException.notFound()
    fun ownedPackage(call: ApplicationCall) =
        store.getPackage(call.pathId("packageId"))?.takeIf { it.ownerId == call.principal.ownerId } ?: throw ApiException.notFound()

    route("/v1") {
        install(AuthPlugin) {
            authenticator = ctx.authenticator
            publicRoutes = setOf("GET /v1/health", "GET /v1/ready", "POST /v1/installs")
        }

        // Liveness: the process is up. Readiness: it can reach its store (a failing store takes the instance out of the load balancer).
        get("/health") { call.respond(HealthResponse("ok", "v1")) }

        get("/ready") {
            val ready = try {
                withContext(Dispatchers.IO) { store.ping() }
                true
            } catch (e: Exception) {
                log.warn("not ready: store check failed ({})", e.javaClass.simpleName)
                false
            }
            if (ready) call.respond(HealthResponse("ready", "v1"))
            else call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(ApiError(ErrorCodes.INTERNAL_ERROR, "The store is not reachable.", retryable = true)))
        }

        post("/installs") {
            ctx.quotas.checkInstallCreation()
            call.readLimitedBody(limits.maxSmallJsonBytes) // body is ignored ({}), but still size-capped
            val issued = ctx.installTokens.issue()
            call.respond(HttpStatusCode.Created, InstallResponse(issued.record.installId, issued.token))
        }

        activityRoutes(ctx)

        run {
            // ---- documents ---------------------------------------------------------------------------------------
            post("/documents") {
                val owner = call.principal
                val type = call.request.contentType()
                val response = when {
                    type.match(ContentType.MultiPart.FormData) -> uploadPdf(call, ctx, owner.ownerId)
                    type.match(ContentType.Application.Json) -> uploadText(call, ctx, owner.ownerId)
                    else -> throw ApiException(HttpStatusCode.UnsupportedMediaType, ErrorCodes.UNSUPPORTED_TYPE, "Send a multipart PDF or a JSON text document.")
                }
                call.respond(HttpStatusCode.Created, response)
            }

            delete("/documents/{documentId}") {
                val doc = ownedDocument(call)
                // stop running work first so nothing is published into a deleted document
                store.listJobsByDocument(doc.documentId).forEach { ctx.runner.cancel(it.jobId) }
                store.deleteDocument(doc.documentId)
                log.info("document {} deleted", doc.documentId)
                call.respond(HttpStatusCode.NoContent)
            }

            // Draft document tutor: separate from compile/practice and protected by the same owner token.
            post("/documents/{documentId}/tutor") {
                val doc = ownedDocument(call)
                val req = call.receiveJson(CreateTutorRequest.serializer(), limits.maxSmallJsonBytes)
                call.respond(HttpStatusCode.Created, ctx.tutor.create(doc, req) { ctx.quotas.consumeCompile(doc.ownerId) })
            }
            get("/tutors/{tutorId}") { call.respond(ctx.tutor.owned(call.pathId("tutorId"), call.principal.ownerId).session) }
            post("/tutors/{tutorId}/assessment") {
                val req = call.receiveJson(AssessTutorRequest.serializer(), limits.maxSmallJsonBytes)
                call.respond(ctx.tutor.assess(call.pathId("tutorId"), call.principal.ownerId, req))
            }
            post("/tutors/{tutorId}/turns") {
                val owner = call.principal.ownerId
                val req = call.receiveJson(TutorTurnRequest.serializer(), limits.maxSmallJsonBytes)
                call.respond(ctx.tutor.turn(call.pathId("tutorId"), owner, req) { ctx.quotas.consumeAsk(owner) })
            }
            post("/tutors/{tutorId}/cancel") {
                val req = call.receiveJson(CreateTutorRequest.serializer(), limits.maxSmallJsonBytes)
                call.respond(ctx.tutor.cancel(call.pathId("tutorId"), call.principal.ownerId, req.clientRequestId))
            }

            // ---- compile jobs ------------------------------------------------------------------------------------
            post("/compile-jobs") {
                val owner = call.principal.ownerId
                val req = call.receiveJson(CompileJobRequest.serializer(), limits.maxSmallJsonBytes)
                if (req.clientRequestId.isBlank() || req.clientRequestId.length > 100) throw ApiException.badRequest("clientRequestId must be 1 to 100 characters.")
                if (req.options.language !in setOf("en", "zh")) throw ApiException.badRequest("options.language must be 'en' or 'zh'.")
                if (req.options.minutesPerLevel !in 1..30) throw ApiException.badRequest("options.minutesPerLevel must be 1 to 30.")
                if (!idShape.matches(req.documentId)) throw ApiException.notFound()
                val stored = store.withOwnerLock(owner) {
                    val doc = store.getDocument(req.documentId)?.takeIf { it.ownerId == owner } ?: throw ApiException.notFound()

                    val mine = store.listJobsByOwner(owner).filter { it.generation == null }
                    // idempotent on clientRequestId
                    mine.firstOrNull { it.clientRequestId == req.clientRequestId }?.let { existing ->
                        if (existing.documentId != req.documentId || existing.options != req.options) {
                            throw ApiException.badRequest("clientRequestId was already used for a different request.")
                        }
                        return@withOwnerLock existing
                    }
                    // same document version + options already compiled (or running): reuse, no new work, no quota
                    mine.firstOrNull {
                        it.documentId == doc.documentId && it.documentVersion == doc.documentVersion && it.options == req.options &&
                            it.state != CompileJobState.FAILED && it.state != CompileJobState.CANCELLED
                    }?.let { return@withOwnerLock it }

                    val now = System.currentTimeMillis()
                    val job = JobRecord(
                        jobId = newId("job_"), ownerId = owner, documentId = doc.documentId, documentVersion = doc.documentVersion,
                        options = req.options, clientRequestId = req.clientRequestId, packageId = newId("pkg_"), createdAt = now, updatedAt = now,
                    )
                    ctx.quotas.admitCompile(job)
                }
                ctx.runner.wake()
                log.info("compile request for document {} uses job {}", req.documentId, stored.jobId)
                call.respond(HttpStatusCode.Accepted, stored.toStatus())
            }

            get("/compile-jobs/{jobId}") { call.respond(ownedJob(call).toStatus()) }

            post("/compile-jobs/{jobId}/cancel") {
                val job = ownedJob(call)
                val result = ctx.runner.cancel(job.jobId) ?: throw ApiException.notFound()
                call.respond(result.toStatus())
            }

            // ---- packages and frames -----------------------------------------------------------------------------
            get("/packages/{packageId}") {
                val stored = ownedPackage(call)
                // The ETag changes when a level is published; contentVersion does not (levels are only appended).
                val etag = "\"${stored.pkg.contentVersion}-${stored.pkg.levels.size}\""
                call.response.header(HttpHeaders.ETag, etag)
                if (call.request.header(HttpHeaders.IfNoneMatch)?.trim() == etag) {
                    call.respond(HttpStatusCode.NotModified)
                } else {
                    call.respond(stored.pkg)
                }
            }

            // Classic needs no overlay: both frame routes answer 204 No Content for it (nothing exists, nothing is missing).
            get("/packages/{packageId}/frames/{frameId}") {
                val stored = ownedPackage(call)
                val frame = parseFrame(call.parameters["frameId"])
                if (frame == FrameId.CLASSIC) return@get call.respond(HttpStatusCode.NoContent)
                val overlay = store.getFrame(stored.pkg.packageId, frame)?.takeIf { it.contentVersion == stored.pkg.contentVersion }
                    ?: throw ApiException.notFound("This frame has not been generated for this package.")
                call.respond(overlay)
            }

            post("/packages/{packageId}/frames/{frameId}") {
                val stored = ownedPackage(call)
                val frame = parseFrame(call.parameters["frameId"])
                if (frame == FrameId.CLASSIC) return@post call.respond(HttpStatusCode.NoContent)
                if (!stored.complete) {
                    throw ApiException(HttpStatusCode.Conflict, ErrorCodes.PACKAGE_NOT_READY, "The package is still being compiled. Try again when the job has finished.", retryable = true)
                }
                val overlay = ctx.frames.ensure(stored.pkg.packageId, frame) ?: return@post call.respond(HttpStatusCode.NoContent)
                call.respond(overlay)
            }

            // ---- ask ---------------------------------------------------------------------------------------------
            post("/ask") {
                val owner = call.principal.ownerId
                val req = call.receiveJson(AskRequest.serializer(), limits.maxSmallJsonBytes)
                if (req.question.isBlank() || req.question.length > limits.maxQuestionChars) {
                    throw ApiException.badRequest("question must be 1 to ${limits.maxQuestionChars} characters.")
                }
                if (!idShape.matches(req.packageId)) throw ApiException.notFound()
                val stored = store.getPackage(req.packageId)?.takeIf { it.ownerId == owner } ?: throw ApiException.notFound()
                ctx.quotas.consumeAsk(owner)
                call.respond(ctx.ask.ask(stored.pkg, req.unitId, req.question))
            }
        }
    }
}

// ---- document upload ------------------------------------------------------------------------------------------

@Suppress("DEPRECATION") // readRemaining(limit) bounds the read; part.dispose is the stable release call
private suspend fun uploadPdf(call: ApplicationCall, ctx: AppContext, ownerId: String): DocumentResponse {
    val limits = ctx.config.limits
    var bytes: ByteArray? = null
    var fileName: String? = null
    var title: String? = null
    // Ktor 3 applies formFieldLimit to EVERY part, file parts included (a 4 KB limit rejected every real PDF —
    // found by the eval harness, 2026-11-18). Ktor's limit is the whole-request cap, so our own PDF check below answers
    // first with FILE_TOO_LARGE; if Ktor's limit still trips (e.g. a chunked body), it is mapped to the same answer.
    try {
    call.receiveMultipart(formFieldLimit = limits.maxRequestBytes.toLong()).forEachPart { part ->
        try {
            when (part) {
                is PartData.FileItem -> if (bytes == null) {
                    fileName = part.originalFileName
                    val data = part.provider().readRemaining(limits.maxPdfBytes.toLong() + 1).readByteArray()
                    if (data.size > limits.maxPdfBytes) throw ApiException.tooLarge("The PDF is larger than ${limits.maxPdfBytes / (1024 * 1024)} MB.")
                    bytes = data
                }
                is PartData.FormItem -> if (part.name == "title") title = part.value
                else -> Unit
            }
        } finally {
            part.dispose()
        }
    }
    } catch (e: java.io.IOException) {
        if (e.message.orEmpty().contains("limit", ignoreCase = true)) throw ApiException.tooLarge("The upload is larger than ${limits.maxRequestBytes / (1024 * 1024)} MB.")
        throw e
    }
    val data = bytes ?: throw ApiException.badRequest("Send the PDF as a multipart file part.")
    // The file name and declared content type are ignored; the magic bytes decide.
    if (!PdfTextExtractor.looksLikePdf(data)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, ErrorCodes.UNSUPPORTED_TYPE, "The file is not a PDF.")
    }
    val displayTitle = title ?: fileName?.substringBeforeLast('.')
    // Glyph geometry only on request: it can be a megabyte of JSON that older phones (2 MB response cap) never asked for.
    if (call.request.queryParameters["layout"] != "1") {
        val pages = withContext(Dispatchers.IO) { ctx.pdf.extract(data) }
        return storeDocument(ctx, ownerId, displayTitle, DocumentKind.PDF, pages)
    }
    val read = withContext(Dispatchers.IO) { ctx.pdf.extractWithGlyphs(data) }
    // Nothing of the layout is stored: the phone keeps its own copy of the PDF and this geometry next to it.
    return storeDocument(ctx, ownerId, displayTitle, DocumentKind.PDF, read.pages) { chunks -> ChunkLayouts.build(chunks, read.glyphs) }
}

private suspend fun uploadText(call: ApplicationCall, ctx: AppContext, ownerId: String): DocumentResponse {
    val limits = ctx.config.limits
    val req = call.receiveJson(TextDocumentRequest.serializer(), limits.maxTextBodyBytes)
    if (req.kind == DocumentKind.PDF) throw ApiException.badRequest("Send PDFs as multipart; JSON documents are text or ocr.")
    if (req.pages.isEmpty()) throw ApiException.badRequest("pages must not be empty.")
    if (req.pages.size > limits.maxTextPages) throw ApiException.tooLarge("At most ${limits.maxTextPages} pages.")
    if (req.pages.any { it.pageIndex < 0 }) throw ApiException.badRequest("pageIndex must not be negative.")
    if (req.pages.map { it.pageIndex }.toSet().size != req.pages.size) throw ApiException.badRequest("pageIndex values must be unique.")
    val pages = req.pages.map { PageText(it.pageIndex, PdfTextExtractor.cleanText(it.text), it.pageLabel?.take(20)) }
    return storeDocument(ctx, ownerId, req.title, req.kind, pages)
}

private fun storeDocument(
    ctx: AppContext, ownerId: String, rawTitle: String?, kind: DocumentKind, pages: List<PageText>,
    layoutOf: ((List<SourceChunk>) -> DocumentLayout)? = null,
): DocumentResponse {
    val limits = ctx.config.limits
    val totalChars = pages.sumOf { it.text.length }
    if (totalChars > limits.maxTextChars) throw ApiException.tooLarge("The text has $totalChars characters; the limit is ${limits.maxTextChars}.")
    if (pages.all { it.text.isBlank() }) {
        throw IngestException(ErrorCodes.NO_TEXT_LAYER, "No text could be found. For scanned pages, photograph them in the app so the text is recognised on your device.")
    }
    val chunks = Chunker.chunk(pages)
    val title = rawTitle?.let { PdfTextExtractor.cleanText(it).replace(Regex("\\s+"), " ") }?.take(200)?.ifBlank { null } ?: "Untitled"
    val doc = StoredDocument(
        documentId = newId("doc_"), ownerId = ownerId, title = title, kind = kind,
        documentVersion = Chunker.documentVersion(pages), pageCount = pages.size, createdAt = System.currentTimeMillis(), chunks = chunks,
    )
    ctx.store.saveDocument(doc)
    return DocumentResponse(doc.documentId, doc.documentVersion, doc.pageCount, doc.title, layoutOf?.invoke(chunks))
}
