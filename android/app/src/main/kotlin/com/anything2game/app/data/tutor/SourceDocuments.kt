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
 * A PDF the reader imported, kept on this phone with the text geometry the server measured in it, so "Show source"
 * can show the real page. Only ever the user's own file (preset courses ship text only); the server keeps no copy.
 */
class SourceDocument(val documentId: String, val documentVersion: String, val pageCount: Int, val file: File, val layout: DocumentLayout)

@Serializable
private data class SourceDocumentRecord(val documentId: String, val documentVersion: String, val pageCount: Int, val layout: DocumentLayout)

/** Per profile, next to the tutor sessions. The record is written after the PDF, so a record always has its file. */
class SourceDocumentStore(private val directory: File) {
    private fun base(documentId: String) =
        MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun pdf(documentId: String) = File(directory, base(documentId) + ".pdf")
    private fun record(documentId: String) = File(directory, base(documentId) + ".layout.json")

    suspend fun save(document: DocumentResponse, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val layout = requireNotNull(document.layout) { "No layout to keep" }
        check(directory.isDirectory || directory.mkdirs())
        write(pdf(document.documentId), bytes)
        write(record(document.documentId), ContractJson.json.encodeToString(SourceDocumentRecord.serializer(),
            SourceDocumentRecord(document.documentId, document.documentVersion, document.pageCount, layout)).toByteArray())
    }

    /** The kept PDF of exactly this document version, or null (never imported here, another version, or a damaged record). */
    suspend fun load(documentId: String, documentVersion: String): SourceDocument? = withContext(Dispatchers.IO) {
        val target = record(documentId)
        val file = pdf(documentId)
        if (!target.exists() && !File(target.path + ".bak").exists()) return@withContext null
        val r = AtomicFile(target).openRead().bufferedReader().use { ContractJson.json.decodeFromString(SourceDocumentRecord.serializer(), it.readText()) }
        if (r.documentId != documentId || r.documentVersion != documentVersion || !file.isFile) null
        else SourceDocument(r.documentId, r.documentVersion, r.pageCount, file, r.layout)
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
