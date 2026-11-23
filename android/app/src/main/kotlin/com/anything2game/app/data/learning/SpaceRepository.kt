package com.anything2game.app.data.learning

import com.anything2game.app.data.*
import com.anything2game.app.data.api.ActivityApi
import com.anything2game.app.data.api.ApiException
import com.anything2game.contract.ApiError
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable private enum class EditKind { CREATE, RENAME, ADD, REMOVE }
@Serializable private data class SpaceEdit(val kind: EditKind, val requestId: String, val title: String? = null,
    val member: SpaceMember? = null, val rejection: ApiError? = null)
@Serializable private data class SavedSpace(val id: String, val remote: Space? = null, val edits: List<SpaceEdit> = emptyList(),
    val installedPath: ContentVersion? = null, val creationStarted: Boolean = false) {
    fun view(): Space {
        var space = remote?.copy(spaceId = id) ?: Space(id, "", 0)
        for (edit in edits) space = when (edit.kind) {
            EditKind.CREATE, EditKind.RENAME -> space.copy(title = requireNotNull(edit.title))
            EditKind.ADD -> space.copy(members = (space.members + requireNotNull(edit.member)).distinct())
            EditKind.REMOVE -> space.copy(members = space.members - requireNotNull(edit.member))
        }
        return space.copy(activePathVersion = installedPath)
    }
}
@Serializable private data class SpaceState(val entries: Map<String, SavedSpace> = emptyMap(), val migrated: Set<String> = emptySet())

/** Device IDs remain stable after the server assigns its owner-scoped identity. Only relationships enter the outbox. */
class SpaceRepository(file: File, private val materials: suspend () -> List<Material>,
    private val api: ActivityApi? = null, private val loadPath: suspend (ContentVersion) -> LearningPath? = { null }) {
    private val disk = AtomicSnapshot(file, SpaceState.serializer(), ::SpaceState)
    private val syncLock = File(file.path + ".sync").mutex()
    private fun id() = UUID.randomUUID().toString()
    private fun creation(id: String, title: String) = SavedSpace(id, edits = listOf(SpaceEdit(EditKind.CREATE, this.id(), title = title)))
    private fun checkTitle(title: String) { require(title.isNotBlank() && title.length <= 120 && title.none(Char::isISOControl)) }

    suspend fun list(): List<Space> {
        val catalog = materials()
        return disk.update { prior ->
            val entries = prior.entries.toMutableMap()
            val migrated = prior.migrated.toMutableSet()
            for (material in catalog) {
                val source = material.source
                val member = material.member
                val key = stableId(source.contentId, source.contentVersion, if (member is SpaceMember.LocalPackRef) "pack" else "document",
                    member.version().contentId, member.version().contentVersion)
                if (!migrated.add(key)) continue
                if (entries.values.any { member in it.view().members }) continue
                val group = when (member) {
                    is SpaceMember.ServerDocumentRef -> stableId("server", source.contentId, source.contentVersion)
                    is SpaceMember.LocalPackRef -> stableId("local", member.packageId, source.contentId, source.contentVersion)
                }
                val id = "source-$group"
                val saved = entries[id] ?: creation(id, material.title.filterNot(Char::isISOControl).trim().ifBlank { source.contentId }.take(120))
                entries[id] = saved.copy(edits = saved.edits + SpaceEdit(EditKind.ADD, this.id(), member = member))
            }
            SpaceState(entries, migrated)
        }.entries.values.map { it.view() }
    }

    suspend fun create(title: String): Space {
        checkTitle(title)
        val space = creation(id(), title.trim())
        disk.update { it.copy(entries = it.entries + (space.id to space)) }
        return space.view()
    }
    private suspend fun edit(id: String, change: (SavedSpace) -> SavedSpace): SavedSpace =
        disk.update { it.copy(entries = it.entries + (id to change(it.entries.getValue(id)))) }.entries.getValue(id)

    suspend fun rename(id: String, title: String): Space {
        checkTitle(title)
        return edit(id) { saved -> if (saved.view().title == title.trim()) saved else
            saved.copy(edits = saved.edits + SpaceEdit(EditKind.RENAME, this.id(), title.trim())) }.view()
    }
    suspend fun add(id: String, member: SpaceMember): Space = edit(id) { saved ->
        if (member in saved.view().members) saved else {
            require(saved.view().members.size < 128)
            saved.copy(edits = saved.edits + SpaceEdit(EditKind.ADD, this.id(), member = member))
        }
    }.view()
    suspend fun remove(id: String, member: SpaceMember): Space = edit(id) { saved ->
        val remaining = saved.copy(edits = saved.edits.filterNot {
            it.kind == EditKind.ADD && it.member == member && it.rejection != null
        })
        if (member !in remaining.view().members) remaining else
            remaining.copy(edits = remaining.edits + SpaceEdit(EditKind.REMOVE, this.id(), member = member))
    }.view()

    /** Each request uses the last acknowledged revision. Lost acknowledgements replay the exact same body and request ID. */
    suspend fun sync(id: String): Space = syncLock.withLock {
        val service = requireNotNull(api)
        while (true) {
            val saved = disk.read().entries.getValue(id)
            val op = saved.edits.firstOrNull() ?: break
            val remote = saved.remote
            op.rejection?.let { throw ApiException(it.code, it.retryable, it.message) }
            val answer = try { when (op.kind) {
                EditKind.CREATE -> {
                    edit(id) { it.copy(creationStarted = true) }
                    service.create(CreateSpaceRequest(requireNotNull(op.title), op.requestId))
                }
                EditKind.RENAME -> service.rename(requireNotNull(remote).spaceId, RenameSpaceRequest(requireNotNull(op.title), remote.revision, op.requestId))
                EditKind.ADD, EditKind.REMOVE -> service.member(requireNotNull(remote).spaceId,
                    SpaceMemberRequest(requireNotNull(op.member), remote.revision, op.requestId), op.kind == EditKind.ADD)
            } } catch (e: ApiException) {
                if (e.definiteRejection) edit(id) { entry -> entry.copy(edits = entry.edits.map {
                    if (it.requestId == op.requestId) it.copy(rejection = e.error()) else it
                }) }
                throw e
            }
            edit(id) { it.copy(remote = answer, edits = it.edits.filterNot { e -> e.requestId == op.requestId }) }
        }
        val remote = requireNotNull(disk.read().entries.getValue(id).remote)
        val latest = service.space(remote.spaceId)
        edit(id) { it.copy(remote = latest) }
        latest
    }

    suspend fun rejectedEdits(id: String): Map<String, ApiError> =
        disk.read().entries.getValue(id).edits.mapNotNull { e -> e.rejection?.let { e.requestId to it } }.toMap()

    suspend fun withdrawRejected(id: String, requestId: String): Space = edit(id) { saved ->
        val op = saved.edits.single { it.requestId == requestId }
        require(op.rejection != null && op.kind != EditKind.CREATE)
        saved.copy(edits = saved.edits - op)
    }.view()

    suspend fun retryRejected(id: String, requestId: String): Space = edit(id) { saved ->
        val failed = saved.edits.single { it.requestId == requestId }
        require(failed.rejection != null)
        saved.copy(edits = saved.edits.map { if (it == failed) it.copy(requestId = this.id(), rejection = null) else it })
    }.view()

    /** After a visible SPACE_STALE, deliberately rebase outstanding edits on the latest server version. */
    suspend fun rebase(id: String): Space = syncLock.withLock {
        val entry = disk.read().entries.getValue(id)
        require(entry.edits.firstOrNull()?.rejection != null) { "Resolve the original request before rebasing" }
        val remote = requireNotNull(entry.remote)
        val latest = requireNotNull(api).space(remote.spaceId)
        edit(id) { saved -> saved.copy(remote = latest, edits = saved.edits.map { it.copy(requestId = this.id(), rejection = null) }) }.view()
    }

    suspend fun refreshRemote(): List<Space> = syncLock.withLock {
        val remote = requireNotNull(api).spaces()
        disk.update { prior ->
            val entries = prior.entries.toMutableMap()
            for (space in remote) {
                val saved = entries.values.firstOrNull { it.remote?.spaceId == space.spaceId }
                // Unacknowledged creation may already exist remotely. Replay it first to bind its device id.
                if (saved == null && entries.values.any { it.remote == null && it.creationStarted }) continue
                if (saved != null && saved.edits.isNotEmpty()) continue
                val entry = saved?.copy(remote = space) ?: SavedSpace(space.spaceId, remote = space)
                entries[entry.id] = entry
            }
            prior.copy(entries = entries)
        }.entries.values.map { it.view() }
    }

    suspend fun activate(version: ContentVersion, path: LearningPath) {
        require(version == ContentVersion(path.pathId, path.pathVersion))
        val local = disk.read().entries.values.single { it.remote?.spaceId == path.spaceId }
        val service = requireNotNull(api)
        syncLock.withLock {
            val latest = service.space(path.spaceId)
            require(latest.activePathVersion == version) { "Path is no longer active" }
            edit(local.id) {
                require(it.edits.isEmpty()) { "Synchronize pending Space edits" }
                it.copy(remote = latest, installedPath = version)
            }
        }
    }

    suspend fun serverState(id: String): Space? = disk.read().entries.getValue(id).remote

    suspend fun pathRequest(id: String, language: String): GenerationInput.SpacePath {
        val remote = sync(id)
        return GenerationInput.SpacePath(remote.spaceId, remote.revision, language)
    }

    suspend fun route(id: String, language: String): LearningPath? {
        val space = list().single { it.spaceId == id }
        val catalog = materials()
        val members = catalog.filter { it.member in space.members }
        val generated = space.activePathVersion?.let { loadPath(it) }
        val includedDocs = members.map { it.source }.toSet() + space.members.filterIsInstance<SpaceMember.ServerDocumentRef>().map { it.version() }
        val generatedNodes = generated?.nodes.orEmpty().filterNot { n -> n.evidence.filterIsInstance<EvidenceRef.Pdf>()
            .any { ContentVersion(it.documentId, it.documentVersion) !in includedDocs } }
        val kept = generatedNodes.map { it.nodeId }.toSet()
        val nodes = generatedNodes.map { it.copy(prerequisites = it.prerequisites.filter(kept::contains)) }.toMutableList()
        val used = nodes.map { it.activity.key("") }.toMutableSet()
        for (node in members.flatMap { it.nodes }) if (used.add(node.activity.key(""))) nodes += node
        if (nodes.isEmpty()) return null
        return LearningPath(generated?.pathId ?: "local-$id", generated?.pathVersion ?: "local", id, space.title,
            generated?.language ?: language, nodes, generated?.excludedMembers.orEmpty().filter { it in space.members })
    }
}
