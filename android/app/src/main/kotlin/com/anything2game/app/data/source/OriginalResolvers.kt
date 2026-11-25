package com.anything2game.app.data.source

import com.anything2game.app.data.tutor.AttachResult
import com.anything2game.app.data.tutor.PresetSources
import com.anything2game.app.data.tutor.SourceDocument
import com.anything2game.app.data.tutor.TutorRepository
import java.io.InputStream

/**
 * The phone's chain, in the order a source's original is looked for: the reader's own import, then a PDF they attached, then
 * the paper the build bundled. A new kind of original is one more resolver in this list; no screen changes.
 */
fun phoneSourceLibrary(tutors: TutorRepository, presets: PresetSources): SourceLibrary = ResolvingSourceLibrary(
    listOf(ImportedPdfResolver(tutors::sourceDocument), AttachedPdfResolver(presets), BundledPdfResolver(presets)), PresetAttachOffers(presets))

/** The reader's own import: the PDF kept on this phone, per profile, when it was uploaded to be turned into a lesson. */
class ImportedPdfResolver(private val kept: suspend (documentId: String, documentVersion: String) -> SourceDocument?) : OriginalResolver {
    override suspend fun find(key: SourceKey): Original? {
        // A source scoped to its course's own paper never borrows an import that merely carries the same document labels.
        if (key.courses.any { it.ownPaperOnly }) return null
        return kept(key.documentId, key.documentVersion)?.let(Original::Pdf)
    }
}

/** A course's PDF the reader attached themselves (checked against the file the course was made from, then kept per profile). */
class AttachedPdfResolver(private val presets: PresetSources) : OriginalResolver {
    override suspend fun find(key: SourceKey): Original? {
        return key.courses.firstNotNullOfOrNull { presets.attached(it.packageId, key.documentId, key.documentVersion, it.contentVersion) }?.let(Original::Pdf)
    }
}

/** The demo paper the build put in the app (fetched and checked at build time), kept per profile on first use. */
class BundledPdfResolver(private val presets: PresetSources) : OriginalResolver {
    override suspend fun find(key: SourceKey): Original? {
        return key.courses.firstNotNullOfOrNull { presets.bundled(it.packageId, key.documentId, key.documentVersion, it.contentVersion) }?.let(Original::Pdf)
    }
}

/** A preset course that ships page geometry can take the reader's copy of its paper. */
class PresetAttachOffers(private val presets: PresetSources) : AttachOffers {
    override suspend fun offer(key: SourceKey): AttachOffer? {
        for (course in key.courses) {
            val expected = presets.available(course.packageId, course.contentVersion)
                ?.takeIf { it.documentId == key.documentId && it.documentVersion == key.documentVersion } ?: continue
            return AttachOffer(expected) { open: () -> InputStream? -> presets.attach(course.packageId, course.contentVersion, open) }
        }
        return null
    }
}
