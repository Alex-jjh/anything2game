package com.anything2game.app.data.learning

import android.net.Uri
import com.anything2game.app.data.*
import com.anything2game.app.data.tutor.*
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.DocumentResponse
import com.anything2game.contract.space.SpaceMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

@Serializable
data class PdfImportItem(val id: String, val spaceId: String, val title: String, val uri: String, val sizeBytes: Long?,
    val staged: Boolean = false, val document: DocumentResponse? = null, val sessionId: String? = null, val error: String? = null)

/** Stage the authorized selection while its temporary grant is alive. Each PDF has its own retry boundary. */
class PdfImportQueue(private val dir: File, private val read: suspend (ImportRequest.PdfFile) -> ByteArray,
    private val tutors: TutorRepository, private val spaces: SpaceRepository) {
    private val rows = AtomicSnapshot(File(dir, "queue.json"), ListSerializer(PdfImportItem.serializer()), ::emptyList)
    suspend fun list() = rows.read()
    private suspend fun save(row: PdfImportItem): PdfImportItem {
        rows.update { all -> all.map { if (it.id == row.id) row else it } }
        return row
    }
    private fun lock(id: String) = File(dir, "row-${stableId(id)}").mutex()
    private fun bytes(row: PdfImportItem) = File(dir, stableId(row.id) + ".pdf")
    private suspend fun stage(row: PdfImportItem): PdfImportItem {
        val file = bytes(row)
        if (!file.exists()) {
            val data = read(ImportRequest.PdfFile(row.title, Uri.parse(row.uri), row.sizeBytes))
            if (!PdfBytes.looksLikePdf(data)) throw TutorApiException("INVALID_PDF", false)
            withContext(Dispatchers.IO) {
                val temp = File.createTempFile("selected", ".part", dir)
                try { temp.outputStream().use { it.write(data); it.fd.sync() }; temp.promote(file) } finally { temp.delete() }
            }
        }
        return save(row.copy(staged = true, error = null))
    }
    private fun errorCode(e: Exception) = (e as? TutorApiException)?.code ?: "CONNECTION_OR_FILE_ERROR"
    suspend fun enqueue(spaceId: String, requests: List<ImportRequest.PdfFile>): List<PdfImportItem> =
        requests.map { request ->
            val row = PdfImportItem(UUID.randomUUID().toString(), spaceId, request.title, request.uri.toString(), request.sizeBytes)
            lock(row.id).withLock {
                rows.update { it + row }
                try { stage(row) } catch (e: CancellationException) { throw e } catch (e: Exception) { save(row.copy(error = errorCode(e))) }
            }
        }

    suspend fun process(id: String): PdfImportItem = lock(id).withLock {
        val row = list().single { it.id == id }
        if (row.error == UPLOAD_OUTCOME_UNKNOWN || row.sessionId != null) row else process(row)
    }

    /** A new POST is deliberate because the old upload may already exist on the server. */
    suspend fun retryUnknownUpload(id: String): PdfImportItem = lock(id).withLock {
        val old = list().single { it.id == id }
        require(old.error == UPLOAD_OUTCOME_UNKNOWN && old.document == null)
        process(old)
    }

    private suspend fun process(initial: PdfImportItem): PdfImportItem {
        var row = initial
        return try {
            val document = row.document ?: run {
                if (!row.staged) row = stage(row)
                val file = bytes(row)
                row = save(row.copy(error = UPLOAD_OUTCOME_UNKNOWN))
                val doc = try {
                    tutors.upload(ImportRequest.PdfFile(row.title, Uri.fromFile(file), file.length()))
                } catch (e: TutorApiException) {
                    if (e.definiteRejection) return save(row.copy(error = e.code))
                    throw e
                }
                row = save(row.copy(document = doc, error = null))
                doc
            }
            val member = SpaceMember.ServerDocumentRef(document.documentId, document.documentVersion)
            spaces.add(row.spaceId, member)
            val session = tutors.create(document, row.id)
            tutors.save(TutorCache(session))
            val complete = save(row.copy(sessionId = session.sessionId, error = null))
            withContext(Dispatchers.IO) { bytes(row).delete() }
            complete
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (row.error == UPLOAD_OUTCOME_UNKNOWN && row.document == null) row else save(row.copy(error = errorCode(e)))
        }
    }

    companion object { const val UPLOAD_OUTCOME_UNKNOWN = "UPLOAD_OUTCOME_UNKNOWN" }
}
