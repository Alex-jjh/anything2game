package com.anything2game.app.data.learning

import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.content.availableFrames
import com.anything2game.app.data.stableId
import com.anything2game.app.data.tutor.*
import com.anything2game.app.ui.play.offeredFrames
import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import com.anything2game.contract.tutor.TutorQuestion
import com.anything2game.engine.progress.NodeStatus
import com.anything2game.engine.progress.ActivityProgress
import com.anything2game.engine.game.completionActivity

data class Material(val source: ContentVersion, val title: String, val member: SpaceMember, val nodes: List<PathNode>,
    val language: String = "", val tutorKey: String? = null, val chunks: List<SourceChunk> = emptyList(), val localPack: SpaceMember.LocalPackRef? = null,
    /** Made by the explicit test generator rather than a model; the route marks its section. */
    val fixture: Boolean = false)

/** Old server paths used document, concept and path language to name their tutor. Only an owned lesson can satisfy those references. */
private fun List<Material>.lesson(ref: ActivityRef.Tutor, language: String): Material? = singleOrNull { material ->
    val key = material.tutorKey ?: return@singleOrNull false
    if (ref.sessionId != null && ref.sessionId != key) return@singleOrNull false
    if (ref.sessionId == null && (material.member !is SpaceMember.ServerDocumentRef || (language.isNotEmpty() && material.language != language)))
        return@singleOrNull false
    material.nodes.any { it.activity == ref.copy(sessionId = key) }
}

internal fun PathNode.bindTutor(materials: List<Material>, language: String): PathNode {
    val tutor = activity as? ActivityRef.Tutor ?: return this
    if (tutor.sessionId != null) return this
    val key = materials.lesson(tutor, language)?.tutorKey ?: return this
    return copy(activity = tutor.copy(sessionId = key))
}

/** One validated device inventory for a refresh, including game completion activities supported by the runtime. */
internal class MaterialInventory(val materials: List<Material>, games: List<ContentLibrary.DeviceGame>) {
    private val keys = (materials.flatMap { m -> m.nodes.map { it.activity } } + games.map { it.spec.completionActivity() })
        .map { it.key("") }.toSet()
    private val versions = keys.map { ContentVersion(it.contentId, it.contentVersion) }.toSet()
    fun contains(ref: ActivityRef) = ref.key("") in keys

    fun statuses(path: LearningPath, profile: String, progress: ActivityProgress, now: Long): Map<String, NodeStatus> {
        val bound = path.copy(nodes = path.nodes.map { it.bindTutor(materials, path.language) })
        val projected = progress.nodes(profile, bound, versions, now)
        return bound.nodes.associate { node -> node.nodeId to if (contains(node.activity)) projected.getValue(node.nodeId) else NodeStatus.NEEDS_UPDATE }
    }
}

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
    private val sessions: com.anything2game.engine.ports.SessionStore? = null, private val authenticated: suspend (ContentPack) -> Boolean = { false },
    private val framePrefs: com.anything2game.app.data.FramePreferences? = null) {
    suspend fun materials(): List<Material> {
        val saved = tutors.list()
        val result = mutableListOf<Material>()
        for (s in saved) {
            val source = ContentVersion(s.documentId, s.documentVersion)
            result += Material(source, s.title, SpaceMember.ServerDocumentRef(s.documentId, s.documentVersion),
                tutorNodes(source, s.sessionId, s.questions, s.sources), s.language, s.sessionId, s.sources, fixture = s.mode == "mock")
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
            val preferred = framePrefs?.get(pkg.packageId)?.takeIf { it in offeredFrames(pkg, availableFrames(pack)) } ?: FrameId.CLASSIC
            val practice = pkg.levels.map { level ->
                val frame = checkpoint?.takeIf { it.levelId == level.levelId }?.frameId ?: preferred
                val ref = ActivityRef.Practice(pkg.packageId, pkg.contentVersion, level.levelId, frame)
                val node = nodeId(ref)
                val evidence = level.units.firstOrNull()?.item?.allSourceRefs?.firstOrNull()?.let { pkg.scopeEvidence(it, "e-$node") }
                PathNode(node, level.title, ref, evidence = listOfNotNull(evidence))
            }
            val nodes = PresetRoute.arrange(pkg, questions, tutorNodes(source, localTutorKey(pkg), questions, pkg.sources), practice)
            result += Material(source, pkg.title, member, nodes, pkg.language, if (questions.isEmpty()) null else localTutorKey(pkg), pkg.sources,
                SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion), fixture = manifest.fixture)
        }
        return result
    }

    private fun nodeId(ref: ActivityRef, vararg identity: String) = "local-" + stableId(ref.key("").activityKind.name, ref.contentId, ref.contentVersion, ref.activityId, *identity)
    private fun tutorNodes(source: ContentVersion, sessionId: String, questions: List<TutorQuestion>, chunks: List<SourceChunk>) = questions.map { q ->
        val ref = ActivityRef.Tutor(source.contentId, source.contentVersion, q.conceptId, sessionId = sessionId)
        val node = nodeId(ref, sessionId)
        val evidence = q.sourceRefs.firstOrNull()?.let { cite -> chunks.firstOrNull { it.chunkId == cite.chunkId }?.let {
            EvidenceRef.Pdf("e-$node", source.contentId, source.contentVersion, it.chunkId, cite.quote, it.pageIndex, it.pageLabel)
        } }
        PathNode(node, q.concept, ref, evidence = listOfNotNull(evidence))
    }

    suspend fun availableDocuments() = materials().filter { it.tutorKey != null }.map { it.source }.toSet()
    suspend fun availableVersions() = content.availableVersions() + availableDocuments()
    internal suspend fun snapshot() = MaterialInventory(materials(), content.games())

    /** Version presence alone cannot prove that the named concept/level actually exists. */
    suspend fun verify(path: LearningPath) {
        val inventory = snapshot()
        for (raw in path.nodes) {
            val node = raw.bindTutor(inventory.materials, path.language)
            require(inventory.contains(node.activity)) { "Missing activity: ${node.activity.activityId}" }
            for (citation in node.evidence.filterIsInstance<EvidenceRef.Pdf>()) {
                require(inventory.materials.any { it.matches(citation) }) { "Missing source: ${citation.documentId} / ${citation.chunkId}" }
            }
        }
    }

    private fun Material.matches(citation: EvidenceRef.Pdf) = source == ContentVersion(citation.documentId, citation.documentVersion) &&
        chunks.any { it.chunkId == citation.chunkId && it.pageIndex == citation.pageIndex &&
            PackageValidator.normalise(citation.quote) in PackageValidator.normalise(it.text) }

    suspend fun resolve(ref: ActivityRef, language: String = ""): OpenActivity? = when (ref) {
        is ActivityRef.Tutor -> materials().lesson(ref, language)?.tutorKey?.let { OpenActivity.Tutor(it, ref.activityId) }
        is ActivityRef.Practice -> content.load(ref.contentId, ref.contentVersion).getOrNull()?.level(ref.activityId)?.let {
            val saved = sessions?.load(ref.contentId, ref.contentVersion)?.takeIf { s -> !s.isFinished && s.levelId == ref.activityId }
            OpenActivity.Practice(if (saved == null) ref else ref.copy(frame = saved.frameId))
        }
        is ActivityRef.Game -> content.game(ContentVersion(ref.contentId, ref.contentVersion))?.takeIf { game ->
            game.completionActivity() == ref
        }?.let { OpenActivity.Game(ref, it) }
    }

    suspend fun nodeStatuses(path: LearningPath, progress: LearningProgress, now: Long) =
        snapshot().statuses(path, progress.profileId, progress.projection(), now)

    suspend fun source(evidence: EvidenceRef, material: Material? = null): SourceTarget? {
        evidence.validate()
        return when (evidence) {
            is EvidenceRef.Reviewed -> SourceTarget.Card(evidence)
            is EvidenceRef.External -> SourceTarget.External(evidence)
            is EvidenceRef.Pdf -> {
                val scoped = material?.takeIf { it.source == ContentVersion(evidence.documentId, evidence.documentVersion) }
                val matches = (scoped?.let(::listOf) ?: materials()).filter { it.matches(evidence) }
                if (matches.isEmpty()) return null
                val reference = SourceRef(evidence.chunkId, evidence.quote)
                val pack = scoped?.member as? SpaceMember.LocalPackRef
                if (pack != null) return SourceTarget.Pdf(scoped.chunks, reference,
                    presets?.load(pack.packageId, evidence.documentId, evidence.documentVersion, pack.contentVersion))
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
