package com.anything2game.app.data.tutor

import com.anything2game.contract.PresetSourceLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Port: the page geometry a preset course's pack carries (`layout.json`, ADR-019), or null. ContentLibrary implements it. */
fun interface SourceLayouts {
    suspend fun sourceLayoutFor(packageId: String): PresetSourceLayout?
    suspend fun sourceLayoutFor(packageId: String, contentVersion: String): PresetSourceLayout? = sourceLayoutFor(packageId)
}

/** What a reader can be told about the one PDF file a preset's geometry was measured on. */
data class PresetPdf(val packageId: String, val documentId: String, val documentVersion: String, val pdfBytes: Long, val pdfSource: String?, val pageCount: Int = 0)

sealed interface AttachResult {
    class Accepted(val document: SourceDocument) : AttachResult
    /** Another file (maybe the same paper re-stamped): its geometry would mark the wrong words, so it is not kept. */
    data class Mismatch(val expected: PresetPdf, val actualBytes: Long) : AttachResult
    data object TooLarge : AttachResult
    data object NotPdf : AttachResult
    data object Unreadable : AttachResult
    /** The course has no packed geometry (text-only preset, server course): nothing to attach to. */
    data object NoLayout : AttachResult
}

/**
 * "Show source" on the real page for preset courses: the pack has the geometry, the reader brings the PDF. A file is
 * kept only if its SHA-256 equals the one the geometry was measured on; it is then stored per profile in the same
 * [SourceDocumentStore] as imported PDFs, under the preset's own documentId and documentVersion, so any lookup by the
 * session's ids finds it. No network: the check is local.
 */
class PresetSources(
    private val layouts: SourceLayouts, private val store: SourceDocumentStore,
    /** The paper the build bundled for this course (fetched and hash-checked at build time), or null. */
    private val bundled: (packageId: String) -> InputStream? = { null },
) {
    private suspend fun layout(packageId: String, version: String? = null): PresetSourceLayout? =
        try { if (version == null) layouts.sourceLayoutFor(packageId) else layouts.sourceLayoutFor(packageId, version) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** The file this course can show pages of, or null when it has no packed geometry (then nothing is offered). */
    suspend fun available(packageId: String, version: String? = null): PresetPdf? = layout(packageId, version)?.let(::describe)

    /** The attached PDF for the session with these ids, if one was attached here and is still the file the pack's geometry is for. */
    suspend fun load(packageId: String, documentId: String, documentVersion: String, contentVersion: String? = null): SourceDocument? {
        val l = layout(packageId, contentVersion)?.takeIf { it.documentId == documentId && it.documentVersion == documentVersion } ?: return null
        return kept(l) ?: store.bundled(l) ?: (attach(l, isBundled = true) { bundled(l.packageId) } as? AttachResult.Accepted)?.document
    }

    private suspend fun kept(l: PresetSourceLayout): SourceDocument? {
        val documentId = l.documentId
        val documentVersion = l.documentVersion
        // A damaged record is "not attached", never a crash on opening a course.
        val kept = try { store.load(documentId, documentVersion, l.pdfSha256) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: return null
        // The pack's geometry, not the record's copy: a newer measurement of the same bytes wins; other bytes never match.
        return if (kept.pdfSha256 == l.pdfSha256) SourceDocument(l.documentId, l.documentVersion, l.pageCount, kept.file, l.layout, l.pdfSha256) else null
    }

    /** Reads the picked file once (bounded), checks it is a PDF and is the exact file measured, then keeps it. */
    suspend fun attach(packageId: String, open: () -> InputStream?): AttachResult = attach(packageId, null, open)
    suspend fun attach(packageId: String, version: String?, open: () -> InputStream?): AttachResult {
        val layout = layout(packageId, version) ?: return AttachResult.NoLayout
        return attach(layout, open = open)
    }

    private suspend fun attach(l: PresetSourceLayout, isBundled: Boolean = false, open: () -> InputStream?): AttachResult {
        val bytes = withContext(Dispatchers.IO) {
            try { open()?.use { PdfBytes.readBounded(it, PdfBytes.MAX + 1) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } ?: return AttachResult.Unreadable
        if (bytes.size > PdfBytes.MAX) return AttachResult.TooLarge
        if (!PdfBytes.looksLikePdf(bytes)) return AttachResult.NotPdf
        val sha = withContext(Dispatchers.Default) { PdfBytes.sha256(bytes) }
        if (sha != l.pdfSha256 || bytes.size.toLong() != l.pdfBytes) return AttachResult.Mismatch(describe(l), bytes.size.toLong())
        try {
            if (isBundled) return AttachResult.Accepted(requireNotNull(store.bundled(l, bytes)))
            store.save(l.documentId, l.documentVersion, l.pageCount, l.layout, bytes, sha)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return AttachResult.Unreadable }
        return kept(l)?.let { AttachResult.Accepted(it) } ?: AttachResult.Unreadable
    }

    private fun describe(l: PresetSourceLayout) = PresetPdf(l.packageId, l.documentId, l.documentVersion, l.pdfBytes, l.pdfSource, l.pageCount)
}
