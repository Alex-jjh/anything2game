package com.anything2game.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request/response bodies of the /v1 HTTP API. Prose: docs/architecture/ARCHITECTURE.md §3.2.

@Serializable
data class InstallResponse(val installId: String, val token: String)

@Serializable
enum class DocumentKind {
    @SerialName("pdf") PDF,
    @SerialName("text") TEXT,
    /** Text recognised on the device from photos. Images never leave the phone. */
    @SerialName("ocr") OCR,
}

/** Body of `POST /v1/documents` for text that was already extracted on the device. PDFs are sent as multipart. */
@Serializable
data class TextDocumentRequest(val kind: DocumentKind, val title: String, val pages: List<PageText>)

@Serializable
data class PageText(val pageIndex: Int, val text: String, val pageLabel: String? = null)

@Serializable
data class DocumentResponse(val documentId: String, val documentVersion: String, val pageCount: Int, val title: String)

@Serializable
data class CompileOptions(val minutesPerLevel: Int = 5, val language: String = "en")

@Serializable
data class CompileJobRequest(val documentId: String, val options: CompileOptions = CompileOptions(), val clientRequestId: String)

@Serializable
enum class CompileJobState {
    @SerialName("queued") QUEUED,
    @SerialName("planning") PLANNING,
    @SerialName("generating") GENERATING,
    @SerialName("ready") READY,
    /** Some levels failed, at least one was published. */
    @SerialName("partial") PARTIAL,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED;

    val isTerminal: Boolean get() = this in setOf(READY, PARTIAL, FAILED, CANCELLED)
}

@Serializable
data class CompileJobStatus(
    val jobId: String,
    val documentId: String,
    val state: CompileJobState,
    val levelsReady: Int = 0,
    val levelsPlanned: Int = 0,
    val packageId: String? = null,
    val error: ApiError? = null,
)

@Serializable
data class AskRequest(val packageId: String, val unitId: String, val question: String, val clientRequestId: String)

@Serializable
data class AskResponse(
    val answer: String,
    val sourceRefs: List<SourceRef>,
    /** False when the material does not contain an answer; [answer] then says so. */
    val grounded: Boolean,
    val requestId: String,
)

@Serializable
data class ApiError(val code: String, val message: String, val retryable: Boolean)

@Serializable
data class ErrorBody(val error: ApiError)

/** Stable error codes. Clients branch on these, never on messages. */
object ErrorCodes {
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"
    const val NO_TEXT_LAYER = "NO_TEXT_LAYER"
    const val ENCRYPTED_PDF = "ENCRYPTED_PDF"
    const val NOT_FOUND = "NOT_FOUND"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val MODEL_TIMEOUT = "MODEL_TIMEOUT"
    const val GENERATION_FAILED = "GENERATION_FAILED"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    /** The package is still compiling; retry when the job is ready. */
    const val PACKAGE_NOT_READY = "PACKAGE_NOT_READY"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    /** Text extraction took longer than the server allows; usually a very large or unusual PDF. */
    const val PDF_TIMEOUT = "PDF_TIMEOUT"
}
