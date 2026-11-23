package com.anything2game.app.data.learning

import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.stableId
import com.anything2game.app.data.tutor.*
import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import com.anything2game.contract.tutor.TutorQuestion
import com.anything2game.engine.progress.NodeStatus
import com.anything2game.engine.game.completionActivity

data class Material(val source: ContentVersion, val title: String, val member: SpaceMember, val nodes: List<PathNode>,
    val language: String = "", val tutorKey: String? = null, val chunks: List<SourceChunk> = emptyList(), val localPack: SpaceMember.LocalPackRef? = null)

sealed interface OpenActivity {
    data class Tutor(val sessionKey: String, val conceptId: String) : OpenActivity
    data class Practice(val activity: ActivityRef.Practice) : OpenActivity
    data class Game(val activity: ActivityRef.Game, val spec: GameSpec) : OpenActivity
}

sealed interface SourceTarget {
    data class Pdf(val chunks: List<SourceChunk>, val ref: SourceRef, val document: SourceDocument?) : SourceTarget
    data class Card(val evidence: EvidenceRef.Reviewed) : SourceTarget
    data class External(val evidence: EvidenceRef.External) : SourceTarget
}

class MaterialCatalog(private val content: ContentLibrary, private val tutors: TutorRepository, private val presets: PresetSources? = null,
    private val sessions: com.anything2game.engine.ports.SessionStore? = null, private val authenticated: suspend (ContentPack) -> Boolean = { false }) {
    suspend fun materials(): List<Material> {
        val saved = tutors.list()
        val result = mutableListOf<Material>()
        for (s in saved) {
            val source = ContentVersion(s.documentId, s.documentVersion)
            result += Material(source, s.title, SpaceMember.ServerDocumentRef(s.documentId, s.documentVersion),
                tutorNodes(source, s.questions, s.sources), s.language, s.sessionId, s.sources)
        }
        for (manifest in content.manifests().filter { it.schema == PACK_SCHEMA_V1 }) {
            val version = ContentVersion(manifest.packageId, manifest.contentVersion)
            val pack = content.course(version) ?: continue
            val pkg = pack.pkg
            val source = ContentVersion(pkg.documentId, pkg.documentVersion)
            val owned = authenticated(pack)
            val member = if (owned) SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)
                else SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion)
            val questions = pack.tutor?.blueprint?.concepts.orEmpty().map { it.question }
            val checkpoint = sessions?.load(pkg.packageId, pkg.contentVersion)?.takeUnless { it.isFinished }
            val nodes = tutorNodes(source, questions, pkg.sources) + pkg.levels.map { level ->
                val frame = checkpoint?.takeIf { it.levelId == level.levelId }?.frameId ?: FrameId.CLASSIC
                val ref = ActivityRef.Practice(pkg.packageId, pkg.contentVersion, level.levelId, frame)
                val node = nodeId(ref)
                val evidence = level.units.firstOrNull()?.item?.allSourceRefs?.firstOrNull()?.let { pkg.scopeEvidence(it, "e-$node") }
                PathNode(node, level.title, ref, evidence = listOfNotNull(evidence))
            }
            result += Material(source, pkg.title, member, nodes, pkg.language, if (questions.isEmpty()) null else localTutorKey(pkg), pkg.sources,
                SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion))
        }
        return result
    }

    private fun nodeId(ref: ActivityRef) = "local-" + stableId(ref.key("").activityKind.name, ref.contentId, ref.contentVersion, ref.activityId)
    private fun tutorNodes(source: ContentVersion, questions: List<TutorQuestion>, chunks: List<SourceChunk>) = questions.map { q ->
        val ref = ActivityRef.Tutor(source.contentId, source.contentVersion, q.conceptId)
        val node = nodeId(ref)
        val evidence = q.sourceRefs.firstOrNull()?.let { cite -> chunks.firstOrNull { it.chunkId == cite.chunkId }?.let {
            EvidenceRef.Pdf("e-$node", source.contentId, source.contentVersion, it.chunkId, cite.quote, it.pageIndex, it.pageLabel)
        } }
        PathNode(node, q.concept, ref, evidence = listOfNotNull(evidence))
    }

    suspend fun availableDocuments() = materials().filter { it.tutorKey != null }.map { it.source }.toSet()
    suspend fun availableVersions() = content.availableVersions() + availableDocuments()

    /** Version presence alone cannot prove that the named concept/level actually exists. */
    suspend fun verify(path: LearningPath) {
        val catalog = materials()
        val activities = catalog.flatMap { it.nodes }.map { it.activity.key("") }.toSet()
        for (node in path.nodes) {
            val activity = node.activity
            val exists = if (activity is ActivityRef.Game) resolve(activity, path.language) != null else activity.key("") in activities
            require(exists) { "Missing activity: ${activity.activityId}" }
            for (citation in node.evidence.filterIsInstance<EvidenceRef.Pdf>()) {
                require(catalog.any { it.matches(citation) }) { "Missing source: ${citation.documentId} / ${citation.chunkId}" }
            }
        }
    }

    private fun Material.matches(citation: EvidenceRef.Pdf) = source == ContentVersion(citation.documentId, citation.documentVersion) &&
        chunks.any { it.chunkId == citation.chunkId && it.pageIndex == citation.pageIndex &&
            PackageValidator.normalise(citation.quote) in PackageValidator.normalise(it.text) }

    suspend fun resolve(ref: ActivityRef, language: String = ""): OpenActivity? = when (ref) {
        is ActivityRef.Tutor -> materials().sortedByDescending { it.language == language }.firstOrNull { m ->
            m.tutorKey != null && m.nodes.any { it.activity == ref }
        }?.tutorKey?.let { OpenActivity.Tutor(it, ref.activityId) }
        is ActivityRef.Practice -> content.load(ref.contentId, ref.contentVersion).getOrNull()?.level(ref.activityId)?.let {
            val saved = sessions?.load(ref.contentId, ref.contentVersion)?.takeIf { s -> !s.isFinished && s.levelId == ref.activityId }
            OpenActivity.Practice(if (saved == null) ref else ref.copy(frame = saved.frameId))
        }
        is ActivityRef.Game -> content.game(ContentVersion(ref.contentId, ref.contentVersion))?.takeIf { game ->
            game.completionActivity() == ref
        }?.let { OpenActivity.Game(ref, it) }
    }

    suspend fun nodeStatuses(path: LearningPath, progress: LearningProgress, now: Long): Map<String, NodeStatus> {
        val local = materials()
        val catalogKeys = local.flatMap { it.nodes }.map { it.activity.key("") }.toSet()
        val versions = content.availableVersions() + local.filter { it.tutorKey != null }.map { it.source }
        val projected = progress.projection().nodes(progress.profileId, path, versions, now)
        return path.nodes.associate { node ->
            val ref = node.activity
            val present = if (ref is ActivityRef.Game) resolve(ref, path.language) != null else ref.key("") in catalogKeys
            node.nodeId to if (present) projected.getValue(node.nodeId) else NodeStatus.NEEDS_UPDATE
        }
    }

    suspend fun source(evidence: EvidenceRef): SourceTarget? {
        evidence.validate()
        return when (evidence) {
            is EvidenceRef.Reviewed -> SourceTarget.Card(evidence)
            is EvidenceRef.External -> SourceTarget.External(evidence)
            is EvidenceRef.Pdf -> {
                val matches = materials().filter { it.matches(evidence) }
                if (matches.isEmpty()) return null
                val reference = SourceRef(evidence.chunkId, evidence.quote)
                val existing = tutors.sourceDocument(evidence.documentId, evidence.documentVersion)
                if (existing != null) return SourceTarget.Pdf(matches.first().chunks, reference, existing)
                for (material in matches) {
                    val local = material.localPack ?: continue
                    val pdf = presets?.load(local.packageId, evidence.documentId, evidence.documentVersion, local.contentVersion)
                    if (pdf != null) {
                        val document = tutors.sourceDocument(evidence.documentId, evidence.documentVersion) ?: pdf
                        return SourceTarget.Pdf(material.chunks, reference, document)
                    }
                }
                SourceTarget.Pdf(matches.first().chunks, reference, null)
            }
        }
    }
}
