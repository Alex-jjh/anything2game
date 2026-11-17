package com.anything2game.app.importing

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat

/** Something the user sent us. Text is kept in memory; files are referenced by URI and not read until upload. */
sealed interface ImportRequest {
    val title: String

    data class SharedText(override val title: String, val text: String) : ImportRequest
    data class PdfFile(override val title: String, val uri: Uri, val sizeBytes: Long?) : ImportRequest
}

/** Turns share/view intents into [ImportRequest]s. Knows Android intents; knows nothing about screens. */
class IntentImportParser(private val resolver: ContentResolver) {

    fun parse(intent: Intent?): ImportRequest? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> parseSend(intent)
            Intent.ACTION_VIEW -> intent.data?.let { pdf(it) }
            else -> null
        }
    }

    private fun parseSend(intent: Intent): ImportRequest? {
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null && (intent.type == PDF || resolver.getType(stream) == PDF)) return pdf(stream)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        return ImportRequest.SharedText(subject.ifEmpty { text.lineSequence().first().take(60) }, text)
    }

    fun pdf(uri: Uri): ImportRequest.PdfFile {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        return ImportRequest.PdfFile(name ?: uri.lastPathSegment ?: "document.pdf", uri, size)
    }

    companion object { const val PDF = "application/pdf" }
}
