package com.anything2game.app.data.source

import com.anything2game.app.data.tutor.AttachResult
import com.anything2game.app.data.tutor.PresetPdf
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.contract.SourceChunk
import java.io.InputStream

/**
 * The course a document belongs to, when it came with one (a preset or an installed course); resolvers that need it ask for it.
 * [ownPaperOnly]: only the course's own paper counts, never a PDF the reader imported under the same document labels.
 */
data class CourseRef(val packageId: String, val contentVersion: String? = null, val ownPaperOnly: Boolean = false)

/**
 * Which file is meant: the document's own ids, plus the courses it came with, if any (more than one when several editions of a
 * course cite the same document; the first one that has the paper wins). Everything a resolver needs to look the original up.
 */
data class SourceKey(val documentId: String, val documentVersion: String, val courses: List<CourseRef> = emptyList()) {
    constructor(documentId: String, documentVersion: String, course: CourseRef?) : this(documentId, documentVersion, listOfNotNull(course))
}

/** The words the caller already holds for the document: its name and the extracted text, chunk by chunk. */
data class SourceText(val title: String, val chunks: List<SourceChunk>)

/**
 * The original of a source, as the phone can show it. [Pdf] is a real PDF file; a deck of slide images would be one more
 * subtype here plus one more renderer in the viewer, and nothing else changes (docs/architecture/source-viewer.md).
 */
sealed interface Original {
    class Pdf(val document: SourceDocument) : Original
}

/** "Attach your PDF": the one thing a reader can do when the course has page geometry but the file is not on the phone. */
class AttachOffer(val expected: PresetPdf, val run: suspend (open: () -> InputStream?) -> AttachResult)

/** What the one viewer shows: the text always, the [original] when the phone has it, and [attach] when it could have it. */
class SourceFile(val title: String, val chunks: List<SourceChunk>, val original: Original?, val attach: AttachOffer? = null)

/** Port: every screen gets its source through this, so each source looks the same wherever it is opened. */
interface SourceLibrary {
    suspend fun open(key: SourceKey, text: SourceText): SourceFile
}

/** One way of finding the original; returns null when it does not have this one. Resolvers know nothing of screens. */
fun interface OriginalResolver {
    suspend fun find(key: SourceKey): Original?
}

/** Where an "attach" can be offered for a key (null: nothing to attach to). */
fun interface AttachOffers {
    suspend fun offer(key: SourceKey): AttachOffer?
}

/**
 * The [SourceLibrary]: the first resolver that has the original wins, in the order given at the composition root.
 * A resolver that fails counts as "not here", so a damaged record shows the text instead of breaking the screen.
 */
class ResolvingSourceLibrary(private val resolvers: List<OriginalResolver>, private val offers: AttachOffers? = null) : SourceLibrary {
    override suspend fun open(key: SourceKey, text: SourceText): SourceFile {
        var original: Original? = null
        for ((i, resolver) in resolvers.withIndex()) {
            original = safely { resolver.find(key) } ?: continue
            // Opening a later source (a bundled paper takes a moment) can overlap with an earlier one appearing, such as an
            // upload finishing: whatever the chain would answer now wins, so the reader's own file is never passed over.
            if (i > 0) original = resolvers.take(i).firstNotNullOfOrNull { safely { it.find(key) } } ?: original
            break
        }
        val offer = if (original == null) offers?.let { safely { it.offer(key) } } else null
        return SourceFile(text.title, text.chunks, original, offer)
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? =
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
}

/** A library with no original files at all: every source shows its text. For previews, tests and screens built without a phone. */
object TextOnlySources : SourceLibrary {
    override suspend fun open(key: SourceKey, text: SourceText) = SourceFile(text.title, text.chunks, original = null)
}
