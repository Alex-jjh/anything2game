package com.anything2game.app.data.tutor

import android.util.AtomicFile
import com.anything2game.app.data.mutex
import com.anything2game.app.data.promote
import com.anything2game.app.data.stableId
import com.anything2game.contract.PresetSourceLayout
import kotlinx.coroutines.sync.withLock
import com.anything2game.contract.ContractJson
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.DocumentResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * A PDF the reader imported, or attached to a preset course, kept on this phone with the text geometry measured in it,
 * so the source viewer can show the real page. Only ever the user's own file: presets ship geometry, never a PDF, and the
 * server keeps no copy. [pdfSha256] is set for an attached preset PDF (whose geometry was measured on exactly those bytes).
 */
class SourceDocument(val documentId: String, val documentVersion: String, val pageCount: Int, val file: File, val layout: DocumentLayout, val pdfSha256: String? = null)

// pdfSha256 is optional so records written before preset attachments existed still decode.
@Serializable
private data class SourceDocumentRecord(val documentId: String, val documentVersion: String, val pageCount: Int,
    val layout: DocumentLayout, val pdfSha256: String? = null, val bytesSha256: String? = null)

/** Per profile, next to the tutor sessions. The record is written after the PDF, so a record always has its file. */
class SourceDocumentStore(private val directory: File) {
    private fun base(documentId: String) =
        MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun edition(id: String, version: String) = "edition-" + stableId(id, version)
    private fun record(edition: String, attachment: String?) =
        File(directory, edition + (attachment?.let { "-$it" } ?: "") + ".layout.json")
    private fun blob(digest: String) = File(directory, "source-$digest.pdf")

    /** An older server sends no layout: the file is kept all the same, shown page by page and opened on the cited page, with no marks. */
    suspend fun save(document: DocumentResponse, bytes: ByteArray) =
        save(document.documentId, document.documentVersion, document.pageCount, document.layout ?: DocumentLayout(), bytes)

    /** Publish one edition only after its exact PDF is durable. Existing records continue pointing to their original bytes. */
    suspend fun save(documentId: String, documentVersion: String, pageCount: Int, layout: DocumentLayout, bytes: ByteArray, pdfSha256: String? = null) = withContext(Dispatchers.IO) {
        val digest = PdfBytes.sha256(bytes)
        require(pdfSha256 == null || pdfSha256 == digest)
        keepBytes(blob(digest), bytes)
        val target = record(edition(documentId, documentVersion), pdfSha256)
        target.mutex().withLock {
            write(target, ContractJson.json.encodeToString(SourceDocumentRecord.serializer(),
                SourceDocumentRecord(documentId, documentVersion, pageCount, layout, pdfSha256, digest)).toByteArray())
        }
    }

    /** The kept PDF of exactly this document version, or null (never imported here, another version, or a damaged record). */
    suspend fun load(documentId: String, documentVersion: String, attachment: String? = null): SourceDocument? = withContext(Dispatchers.IO) {
        val edition = edition(documentId, documentVersion)
        if (attachment != null && !attachment.matches(Regex("[0-9a-f]{64}"))) return@withContext null
        suspend fun open(target: File, legacy: Boolean = false): SourceDocument? = target.mutex().withLock {
            if (!target.isFile && !File(target.path + ".bak").isFile) return@withLock null
            val r = AtomicFile(target).openRead().bufferedReader().use { ContractJson.json.decodeFromString(SourceDocumentRecord.serializer(), it.readText()) }
            if (r.documentId != documentId || r.documentVersion != documentVersion || (attachment != null && r.pdfSha256 != attachment)) return@withLock null
            val file = if (!legacy) {
                val digest = r.bytesSha256?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return@withLock null
                blob(digest)
            } else File(directory, base(documentId) + ".pdf")
            if (!file.isFile) return@withLock null
            if (legacy && r.pdfSha256 != null && PdfBytes.sha256(file.readBytes()) != r.pdfSha256) return@withLock null
            SourceDocument(r.documentId, r.documentVersion, r.pageCount, file, r.layout, r.pdfSha256)
        }
        open(record(edition, attachment))?.let { return@withContext it }
        open(File(directory, base(documentId) + ".layout.json"), legacy = true)?.let { return@withContext it }
        // An unqualified session can use a sole attachment. Multiple printed editions require the pack's measured hash.
        if (attachment != null) return@withContext null
        val only = directory.list().orEmpty().map { it.removeSuffix(".bak") }
            .filter { it.startsWith("$edition-") && it.endsWith(".layout.json") }.distinct().singleOrNull()
        only?.let { open(File(directory, it)) }
    }

    /** Automatic bundled copies have their own content-addressed files, so reading a preset cannot replace an import. */
    internal suspend fun bundled(layout: PresetSourceLayout, verifiedBytes: ByteArray? = null): SourceDocument? = withContext(Dispatchers.IO) {
        val file = File(directory, base(layout.pdfSha256) + ".bundled.pdf")
        if (keepBytes(file, verifiedBytes)) SourceDocument(layout.documentId, layout.documentVersion, layout.pageCount, file, layout.layout, layout.pdfSha256)
        else null
    }

    private suspend fun keepBytes(file: File, bytes: ByteArray?): Boolean = file.mutex().withLock {
        if (file.isFile) return@withLock true
        if (bytes == null) return@withLock false
        check(directory.mkdirs() || directory.isDirectory)
        val tmp = File.createTempFile("source", ".part", directory)
        try {
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            tmp.promote(file)
        } finally { tmp.delete() }
        true
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
