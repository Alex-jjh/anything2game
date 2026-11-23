package com.anything2game.app.data.tutor

import android.util.AtomicFile
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
 * so "Show source" can show the real page. Only ever the user's own file: presets ship geometry, never a PDF, and the
 * server keeps no copy. [pdfSha256] is set for an attached preset PDF (whose geometry was measured on exactly those bytes).
 */
class SourceDocument(val documentId: String, val documentVersion: String, val pageCount: Int, val file: File, val layout: DocumentLayout, val pdfSha256: String? = null)

// pdfSha256 is optional so records written before preset attachments existed still decode.
@Serializable
private data class SourceDocumentRecord(val documentId: String, val documentVersion: String, val pageCount: Int, val layout: DocumentLayout, val pdfSha256: String? = null)

/** Per profile, next to the tutor sessions. The record is written after the PDF, so a record always has its file. */
class SourceDocumentStore(private val directory: File) {
    private fun base(documentId: String) =
        MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun pdf(documentId: String) = File(directory, base(documentId) + ".pdf")
    private fun record(documentId: String) = File(directory, base(documentId) + ".layout.json")

    suspend fun save(document: DocumentResponse, bytes: ByteArray) =
        save(document.documentId, document.documentVersion, document.pageCount, requireNotNull(document.layout) { "No layout to keep" }, bytes)

    /** Replaces whatever was kept for [documentId] (another version, an earlier attachment). PDF first, record second. */
    suspend fun save(documentId: String, documentVersion: String, pageCount: Int, layout: DocumentLayout, bytes: ByteArray, pdfSha256: String? = null) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        write(pdf(documentId), bytes)
        write(record(documentId), ContractJson.json.encodeToString(SourceDocumentRecord.serializer(),
            SourceDocumentRecord(documentId, documentVersion, pageCount, layout, pdfSha256)).toByteArray())
    }

    /** The kept PDF of exactly this document version, or null (never imported here, another version, or a damaged record). */
    suspend fun load(documentId: String, documentVersion: String): SourceDocument? = withContext(Dispatchers.IO) {
        val target = record(documentId)
        val file = pdf(documentId)
        if (!target.exists() && !File(target.path + ".bak").exists()) return@withContext null
        val r = AtomicFile(target).openRead().bufferedReader().use { ContractJson.json.decodeFromString(SourceDocumentRecord.serializer(), it.readText()) }
        if (r.documentId != documentId || r.documentVersion != documentVersion || !file.isFile) null
        else SourceDocument(r.documentId, r.documentVersion, r.pageCount, file, r.layout, r.pdfSha256)
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
