package com.anything2game.server.api

import com.anything2game.contract.AskRequest
import com.anything2game.contract.CompileJobRequest
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.DocumentKind
import com.anything2game.contract.DocumentResponse
import com.anything2game.contract.ErrorCodes
import com.anything2game.contract.FrameId
import com.anything2game.contract.InstallResponse
import com.anything2game.contract.PageText
import com.anything2game.contract.TextDocumentRequest
import com.anything2game.server.AppContext
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.ingest.Chunker
import com.anything2game.server.ingest.IngestException
import com.anything2game.server.ingest.PdfTextExtractor
import com.anything2game.server.store.InstallRecord
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

private fun ApplicationCall.pathId(name: String): String =
    parameters[name]?.takeIf { idShape.matches(it) } ?: throw ApiException.notFound()

private fun parseFrame(raw: String?): FrameId =
    FrameId.entries.firstOrNull { ServerConfig.frameName(it) == raw } ?: throw ApiException.badRequest("Unknown frame '${raw?.take(40)}'.")

fun Route.apiRoutes(ctx: AppContext) {
    val log = LoggerFactory.getLogger("com.anything2game.server.api")
    val limits = ctx.config.limits
    val store = ctx.store

    // ownership: a resource of another install is indistinguishable from one that does not exist
    fun ownedDocument(call: ApplicationCall): StoredDocument =
        store.getDocument(call.pathId("documentId"))?.takeIf { it.installId == call.install.installId } ?: throw ApiException.notFound()
    fun ownedJob(call: ApplicationCall): JobRecord =
        store.getJob(call.pathId("jobId"))?.takeIf { it.installId == call.install.installId } ?: throw ApiException.notFound()
    fun ownedPackage(call: ApplicationCall) =
        store.getPackage(call.pathId("packageId"))?.takeIf { it.installId == call.install.installId } ?: throw ApiException.notFound()

    route("/v1") {
        install(InstallAuthPlugin) {
            auth = ctx.auth
            publicRoutes = setOf("GET /v1/health", "POST /v1/installs")
        }

        get("/health") { call.respond(HealthResponse("ok", "v1")) }

        post("/installs") {
            ctx.quotas.checkInstallCreation()
            call.readLimitedBody(limits.maxSmallJsonBytes) // body is ignored ({}), but still size-capped
            val issued = ctx.auth.issue()
            call.respond(HttpStatusCode.Created, InstallResponse(issued.record.installId, issued.token))
        }

        run {
            // ---- documents ---------------------------------------------------------------------------------------
            post("/documents") {
                val install = call.install
                val type = call.request.contentType()
                val response = when {
                    type.match(ContentType.MultiPart.FormData) -> uploadPdf(call, ctx, install)
                    type.match(ContentType.Application.Json) -> uploadText(call, ctx, install)
                    else -> throw ApiException(HttpStatusCode.UnsupportedMediaType, ErrorCodes.UNSUPPORTED_TYPE, "Send a multipart PDF or a JSON text document.")
                }
                call.respond(HttpStatusCode.Created, response)
            }

            delete("/documents/{documentId}") {
                val doc = ownedDocument(call)
                // stop running work first so nothing is published into a deleted document
                store.listJobs().filter { it.documentId == doc.documentId }.forEach { ctx.runner.cancel(it.jobId) }
                store.deleteDocument(doc.documentId)
                log.info("document {} deleted", doc.documentId)
                call.respond(HttpStatusCode.NoContent)
            }

            // ---- compile jobs ------------------------------------------------------------------------------------
            post("/compile-jobs") {
                val install = call.install
                val req = call.receiveJson(CompileJobRequest.serializer(), limits.maxSmallJsonBytes)
                if (req.clientRequestId.isBlank() || req.clientRequestId.length > 100) throw ApiException.badRequest("clientRequestId must be 1 to 100 characters.")
                if (req.options.language !in setOf("en", "zh")) throw ApiException.badRequest("options.language must be 'en' or 'zh'.")
                if (req.options.minutesPerLevel !in 1..30) throw ApiException.badRequest("options.minutesPerLevel must be 1 to 30.")
                if (!idShape.matches(req.documentId)) throw ApiException.notFound()
                val doc = store.getDocument(req.documentId)?.takeIf { it.installId == install.installId } ?: throw ApiException.notFound()

                val mine = store.listJobs().filter { it.installId == install.installId }
                // idempotent on clientRequestId
                mine.firstOrNull { it.clientRequestId == req.clientRequestId }?.let { existing ->
                    if (existing.documentId != req.documentId || existing.options != req.options) {
                        throw ApiException.badRequest("clientRequestId was already used for a different request.")
                    }
                    return@post call.respond(HttpStatusCode.Accepted, existing.toStatus())
                }
                // same document version + options already compiled (or running): reuse, no new work, no quota
                mine.firstOrNull {
                    it.documentId == doc.documentId && it.documentVersion == doc.documentVersion && it.options == req.options &&
                        it.state != CompileJobState.FAILED && it.state != CompileJobState.CANCELLED
                }?.let { return@post call.respond(HttpStatusCode.Accepted, it.toStatus()) }

                ctx.quotas.consumeCompile(install)
                val now = System.currentTimeMillis()
                val job = JobRecord(
                    jobId = newId("job_"), installId = install.installId, documentId = doc.documentId, documentVersion = doc.documentVersion,
                    options = req.options, clientRequestId = req.clientRequestId, packageId = newId("pkg_"), createdAt = now, updatedAt = now,
                )
                store.saveJob(job)
                ctx.runner.submit(job.jobId)
                log.info("job {} queued for document {}", job.jobId, doc.documentId)
                call.respond(HttpStatusCode.Accepted, job.toStatus())
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
                val install = call.install
                val req = call.receiveJson(AskRequest.serializer(), limits.maxSmallJsonBytes)
                if (req.question.isBlank() || req.question.length > limits.maxQuestionChars) {
                    throw ApiException.badRequest("question must be 1 to ${limits.maxQuestionChars} characters.")
                }
                if (!idShape.matches(req.packageId)) throw ApiException.notFound()
                val stored = store.getPackage(req.packageId)?.takeIf { it.installId == install.installId } ?: throw ApiException.notFound()
                ctx.quotas.consumeAsk(install)
                call.respond(ctx.ask.ask(stored.pkg, req.unitId, req.question))
            }
        }
    }
}

// ---- document upload ------------------------------------------------------------------------------------------

@Suppress("DEPRECATION") // readRemaining(limit) bounds the read; part.dispose is the stable release call
private suspend fun uploadPdf(call: ApplicationCall, ctx: AppContext, install: InstallRecord): DocumentResponse {
    val limits = ctx.config.limits
    var bytes: ByteArray? = null
    var fileName: String? = null
    var title: String? = null
    call.receiveMultipart(formFieldLimit = 4096).forEachPart { part ->
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
    val data = bytes ?: throw ApiException.badRequest("Send the PDF as a multipart file part.")
    // The file name and declared content type are ignored; the magic bytes decide.
    if (!PdfTextExtractor.looksLikePdf(data)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, ErrorCodes.UNSUPPORTED_TYPE, "The file is not a PDF.")
    }
    val pages = withContext(Dispatchers.IO) { ctx.pdf.extract(data) }
    val displayTitle = title ?: fileName?.substringBeforeLast('.')
    return storeDocument(ctx, install, displayTitle, DocumentKind.PDF, pages)
}

private suspend fun uploadText(call: ApplicationCall, ctx: AppContext, install: InstallRecord): DocumentResponse {
    val limits = ctx.config.limits
    val req = call.receiveJson(TextDocumentRequest.serializer(), limits.maxTextBodyBytes)
    if (req.kind == DocumentKind.PDF) throw ApiException.badRequest("Send PDFs as multipart; JSON documents are text or ocr.")
    if (req.pages.isEmpty()) throw ApiException.badRequest("pages must not be empty.")
    if (req.pages.size > limits.maxTextPages) throw ApiException.tooLarge("At most ${limits.maxTextPages} pages.")
    if (req.pages.any { it.pageIndex < 0 }) throw ApiException.badRequest("pageIndex must not be negative.")
    if (req.pages.map { it.pageIndex }.toSet().size != req.pages.size) throw ApiException.badRequest("pageIndex values must be unique.")
    val pages = req.pages.map { PageText(it.pageIndex, PdfTextExtractor.cleanText(it.text), it.pageLabel?.take(20)) }
    return storeDocument(ctx, install, req.title, req.kind, pages)
}

private fun storeDocument(ctx: AppContext, install: InstallRecord, rawTitle: String?, kind: DocumentKind, pages: List<PageText>): DocumentResponse {
    val limits = ctx.config.limits
    val totalChars = pages.sumOf { it.text.length }
    if (totalChars > limits.maxTextChars) throw ApiException.tooLarge("The text has $totalChars characters; the limit is ${limits.maxTextChars}.")
    if (pages.all { it.text.isBlank() }) {
        throw IngestException(ErrorCodes.NO_TEXT_LAYER, "No text could be found. For scanned pages, photograph them in the app so the text is recognised on your device.")
    }
    val chunks = Chunker.chunk(pages)
    val title = rawTitle?.let { PdfTextExtractor.cleanText(it).replace(Regex("\\s+"), " ") }?.take(200)?.ifBlank { null } ?: "Untitled"
    val doc = StoredDocument(
        documentId = newId("doc_"), installId = install.installId, title = title, kind = kind,
        documentVersion = Chunker.documentVersion(pages), pageCount = pages.size, createdAt = System.currentTimeMillis(), chunks = chunks,
    )
    ctx.store.saveDocument(doc)
    return DocumentResponse(doc.documentId, doc.documentVersion, doc.pageCount, doc.title)
}
