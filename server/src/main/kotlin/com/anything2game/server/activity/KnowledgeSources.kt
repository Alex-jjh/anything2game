package com.anything2game.server.activity

import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import com.anything2game.server.store.*
import com.anything2game.server.tutor.TutorService
import java.util.Locale

/** An approved instructional reference for the requested topic. No query text goes to an external source. */
fun interface KnowledgeSourceProvider {
    fun reviewed(input: GenerationInput.ConceptGame): GameSpec?
}

object ReviewedKnowledge : KnowledgeSourceProvider {
    override fun reviewed(input: GenerationInput.ConceptGame): GameSpec? {
        val topic = input.topic.trim().lowercase(Locale.ROOT).replace(Regex("[-_\\s]+"), " ")
        return when (topic) {
            "binary search", "binary search algorithm", "ordered search", "information partition", "二分搜索", "二分查找", "信息划分" ->
                ReviewedGames.partition(input.language)
            "opportunity cost", "opportunity costs", "resource allocation", "finite resource allocation", "production possibilities frontier",
            "机会成本", "资源分配", "有限资源配置", "有限资源分配", "生产可能性边界" -> ReviewedGames.allocation(input.language)
            else -> null
        }
    }
}

internal data class PathCatalog(val nodes: List<PathNode>, val excluded: List<SpaceMember>)

/** Only small excerpts from actual runnable activities enter a model request. Local references never enter it. */
internal class PathSources(private val store: Store, private val tutors: TutorService) {
    fun collect(owner: String, space: Space, language: String): PathCatalog {
        val nodes = mutableListOf<PathNode>()
        val excluded = mutableListOf<SpaceMember>()
        for ((index, member) in space.members.withIndex()) {
            val before = nodes.size
            if (nodes.size < 16 && member is SpaceMember.ServerDocumentRef) {
                val doc = store.getDocument(member.documentId)?.takeIf { it.ownerId == owner && it.documentVersion == member.documentVersion }
                if (doc != null) {
                    val tutor = store.getTutor(tutors.sessionId(doc, language))?.takeIf { it.ownerId == owner &&
                        it.session.documentVersion == doc.documentVersion && it.session.documentId == doc.documentId }
                    val question = tutor?.session?.questions?.firstOrNull()
                    if (question != null) {
                        val id = "tutor-$index"
                        val ref = evidence(doc, question.sourceRefs.firstOrNull(), id)
                        if (ref != null) nodes += PathNode(id, question.concept.take(120),
                            ActivityRef.Tutor(doc.documentId, doc.documentVersion, question.conceptId), evidence = listOf(ref))
                    }
                    // Prefer the newest matching-language practice; keep the newest other-language practice as a fallback.
                    val packages = store.listJobsByDocument(doc.documentId)
                        .sortedWith(compareByDescending<JobRecord> { it.createdAt }.thenBy { it.jobId })
                        .filter { it.ownerId == owner && it.documentVersion == doc.documentVersion && it.state.isTerminal }
                        .mapNotNull { store.getPackage(it.packageId) }.filter { it.complete && it.ownerId == owner &&
                            it.pkg.documentId == doc.documentId && it.pkg.documentVersion == doc.documentVersion && it.pkg.levels.isNotEmpty() }
                    val pkg = (packages.firstOrNull { it.pkg.language == language } ?: packages.firstOrNull())?.pkg
                    val level = pkg?.levels?.firstOrNull()
                    if (level != null && nodes.size < 16) {
                        val id = "practice-$index"
                        val ref = evidence(doc, level.units.firstOrNull()?.item?.allSourceRefs?.firstOrNull(), id)
                        if (ref != null) nodes += PathNode(id, level.title.take(120),
                            ActivityRef.Practice(pkg.packageId, pkg.contentVersion, level.levelId), evidence = listOf(ref))
                    }
                }
            }
            if (before == nodes.size) excluded += member
        }
        return PathCatalog(nodes, excluded)
    }

    private fun evidence(doc: StoredDocument, source: SourceRef?, id: String): EvidenceRef.Pdf? {
        if (source == null) return null
        val chunk = doc.chunks.firstOrNull { it.chunkId == source.chunkId } ?: return null
        val quote = source.quote.trim().take(240)
        if (quote.isBlank() || PackageValidator.normalise(quote) !in PackageValidator.normalise(chunk.text)) return null
        return EvidenceRef.Pdf("e-$id", doc.documentId, doc.documentVersion, chunk.chunkId, quote, chunk.pageIndex, chunk.pageLabel)
    }
}
