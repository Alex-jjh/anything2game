package com.anything2game.server

import com.anything2game.contract.ApiError
import com.anything2game.contract.AskRequest
import com.anything2game.contract.CompileJobRequest
import com.anything2game.contract.CompileJobStatus
import com.anything2game.contract.CompileOptions
import com.anything2game.contract.ContractJson
import com.anything2game.contract.DocumentKind
import com.anything2game.contract.DocumentResponse
import com.anything2game.contract.ErrorBody
import com.anything2game.contract.InstallResponse
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PageText
import com.anything2game.contract.TextDocumentRequest
import com.anything2game.server.api.module
import com.anything2game.server.config.Limits
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.llm.FakeLlmClient
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.LlmRequest
import com.anything2game.server.llm.LlmRouter
import com.anything2game.server.store.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Reads canonical files from contract/examples (path injected by Gradle). */
object Examples {
    private val dir = File(System.getProperty("contract.examples") ?: "contract/examples")
    fun pkg(name: String): LearningPackage = ContractJson.decodePackage(File(dir, name).readText())
    val retrieval: LearningPackage get() = pkg("package.v1.example.json")
    val courtyardZh: LearningPackage get() = pkg("package.v1.courtyard.zh.json")
}

/**
 * One server instance on a temp data dir under server/build/ (deleted on close), wired to the given model.
 * No network, no keys.
 */
class TestEnv(
    limits: Limits = Limits(),
    val llm: LlmClient = FakeLlmClient(),
    askTimeoutMillis: Long = 5_000,
) : AutoCloseable {
    val dir: File = File(System.getProperty("server.test.tmp") ?: "build/test-data").resolve("t-" + UUID.randomUUID()).also { it.mkdirs() }
    val config = ServerConfig(dataDir = dir.toPath(), limits = limits, askTimeoutMillis = askTimeoutMillis)
    val store = FileStore(dir.toPath())
    val ctx = AppContext(config, store, LlmRouter(llm, llm, llm))

    override fun close() {
        ctx.close()
        dir.deleteRecursively()
    }

    fun run(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(ctx) }
        block()
    }
}

fun <T> TestEnv.use(block: (TestEnv) -> T): T = try { block(this) } finally { close() }

/** Thin typed wrapper over the HTTP API. */
class Api(val client: HttpClient) {
    suspend fun send(method: HttpMethod, path: String, token: String?, body: String? = null, extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        client.request(path) {
            this.method = method
            auth(token)
            if (body != null) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
            extra()
        }

    suspend fun install(): Pair<String, String> {
        val r = send(HttpMethod.Post, "/v1/installs", null, "{}")
        check(r.status == HttpStatusCode.Created) { "install failed: ${r.status}" }
        val i = decode(InstallResponse.serializer(), r)
        return i.installId to i.token
    }

    suspend fun uploadText(token: String, pages: List<String>, title: String = "Test doc", kind: DocumentKind = DocumentKind.TEXT): HttpResponse =
        send(
            HttpMethod.Post, "/v1/documents", token,
            ContractJson.json.encodeToString(TextDocumentRequest.serializer(), TextDocumentRequest(kind, title, pages.mapIndexed { i, t -> PageText(i, t, "${i + 1}") })),
        )

    suspend fun uploadPdf(token: String, bytes: ByteArray, fileName: String = "x.pdf", contentType: String = "application/pdf"): HttpResponse =
        client.submitFormWithBinaryData(
            url = "/v1/documents",
            formData = formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        ) { auth(token) }

    suspend fun document(token: String, pages: List<String>, title: String = "Test doc"): DocumentResponse {
        val r = uploadText(token, pages, title)
        check(r.status == HttpStatusCode.Created) { "upload failed: ${r.status} ${r.bodyAsText()}" }
        return decode(DocumentResponse.serializer(), r)
    }

    suspend fun compile(token: String, documentId: String, clientRequestId: String = UUID.randomUUID().toString(), options: CompileOptions = CompileOptions()): HttpResponse =
        send(HttpMethod.Post, "/v1/compile-jobs", token, ContractJson.json.encodeToString(CompileJobRequest.serializer(), CompileJobRequest(documentId, options, clientRequestId)))

    suspend fun job(token: String, jobId: String): CompileJobStatus = decode(CompileJobStatus.serializer(), send(HttpMethod.Get, "/v1/compile-jobs/$jobId", token))

    suspend fun awaitJob(token: String, jobId: String, timeoutMs: Long = 30_000, until: (CompileJobStatus) -> Boolean = { it.state.isTerminal }): CompileJobStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val s = job(token, jobId)
            if (until(s)) return s
            check(System.currentTimeMillis() < deadline) { "job $jobId did not reach the expected state, last: $s" }
            withContext(Dispatchers.Default) { delay(25) }
        }
    }

    suspend fun pkg(token: String, packageId: String): LearningPackage = decode(LearningPackage.serializer(), send(HttpMethod.Get, "/v1/packages/$packageId", token))

    suspend fun ask(token: String, packageId: String, unitId: String, question: String): HttpResponse =
        send(HttpMethod.Post, "/v1/ask", token, ContractJson.json.encodeToString(AskRequest.serializer(), AskRequest(packageId, unitId, question, UUID.randomUUID().toString())))

    suspend fun error(r: HttpResponse): ApiError = decode(ErrorBody.serializer(), r).error

    /** Upload text, compile, wait for the terminal state. */
    suspend fun compiled(token: String, pages: List<String>, options: CompileOptions = CompileOptions()): Triple<DocumentResponse, CompileJobStatus, LearningPackage?> {
        val doc = document(token, pages)
        val started = decode(CompileJobStatus.serializer(), compile(token, doc.documentId, options = options))
        val done = awaitJob(token, started.jobId)
        return Triple(doc, done, done.packageId?.let { pkg(token, it) })
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String?) { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    suspend fun <T> decode(serializer: KSerializer<T>, r: HttpResponse): T = ContractJson.json.decodeFromString(serializer, r.bodyAsText())
}

fun ApplicationTestBuilder.api() = Api(client)

/** Source texts of the canonical examples as upload pages (one entry per page, chunks joined by blank lines). */
fun pagesOf(pkg: LearningPackage): List<String> =
    pkg.sources.groupBy { it.pageIndex }.toSortedMap().values.map { chunks -> chunks.joinToString("\n\n") { it.text } }

/** A multi-page PDF built with PDFBox. A page with an empty string has no text (like a scan). */
fun makePdf(pages: List<String>, userPassword: String? = null, ownerPassword: String? = null): ByteArray {
    PDDocument().use { doc ->
        for (text in pages) {
            val page = PDPage()
            doc.addPage(page)
            if (text.isNotEmpty()) {
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText(text)
                    cs.endText()
                }
            }
        }
        if (userPassword != null || ownerPassword != null) {
            val policy = StandardProtectionPolicy(ownerPassword ?: "owner-secret", userPassword ?: "", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
        }
        return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
}

/** Records every request and delegates; optionally rewrites answers (to simulate bad model output). */
open class RecordingLlm(
    private val delegate: LlmClient = FakeLlmClient(),
    private val rewrite: (LlmRequest, Int, String) -> String = { _, _, out -> out },
) : LlmClient {
    override val modelId get() = delegate.modelId
    val requests = CopyOnWriteArrayList<LlmRequest>()
    private val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun count(label: String) = requests.count { it.label == label }

    override suspend fun completeJson(request: LlmRequest): String {
        requests += request
        val n = counts.computeIfAbsent(request.label) { AtomicInteger() }.incrementAndGet()
        return rewrite(request, n, delegate.completeJson(request))
    }
}

/** Blocks level generation until released; the wait ignores cancellation, like a provider call that returns late. */
class GatedLlm(private val delegate: LlmClient = FakeLlmClient()) : LlmClient {
    override val modelId get() = delegate.modelId
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    override suspend fun completeJson(request: LlmRequest): String {
        if (request.label.startsWith("level")) {
            started.complete(Unit)
            withContext(NonCancellable) { gate.await() }
        }
        return delegate.completeJson(request)
    }
}

/** Never answers within the timeout. */
class SlowLlm(private val delay: Long) : LlmClient {
    override val modelId = "slow"
    override suspend fun completeJson(request: LlmRequest): String { delay(delay); return "{}" }
}
