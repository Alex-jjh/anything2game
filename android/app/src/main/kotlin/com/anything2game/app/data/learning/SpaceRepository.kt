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
    val member: SpaceMember? = null, val rejection: ApiError? = null, val verificationFor: SpaceMember? = null)
@Serializable private data class SavedSpace(val id: String, val remote: Space? = null, val edits: List<SpaceEdit> = emptyList(),
    val installedPath: ContentVersion? = null, val creationStarted: Boolean = false) {
    fun view(aliases: Map<SpaceMember, SpaceMember> = emptyMap()): Space {
        fun canonical(member: SpaceMember) = aliases[member] ?: member
        var space = remote?.copy(spaceId = id) ?: Space(id, "", 0)
        if (aliases.isNotEmpty()) space = space.copy(members = space.members.map(::canonical).distinct())
        for (edit in edits) space = when (edit.kind) {
            EditKind.CREATE, EditKind.RENAME -> space.copy(title = requireNotNull(edit.title))
            EditKind.ADD -> space.copy(members = (space.members + canonical(requireNotNull(edit.member))).distinct())
            EditKind.REMOVE -> space.copy(members = space.members - canonical(requireNotNull(edit.member)))
        }
        return space.copy(activePathVersion = installedPath)
    }
}
@Serializable private data class SpaceState(val entries: Map<String, SavedSpace> = emptyMap(), val migrated: Set<String> = emptySet())

private fun List<Material>.verifiedAliases(): Map<SpaceMember, SpaceMember> = mapNotNull { material ->
    val document = material.member as? SpaceMember.ServerDocumentRef ?: return@mapNotNull null
    material.localPack?.let { it to document }
}.toMap()

/** Device IDs remain stable after the server assigns its owner-scoped identity. Only relationships enter the outbox. */
class SpaceRepository(file: File, private val materials: suspend () -> List<Material>,
    private val api: ActivityApi? = null, private val loadPath: suspend (ContentVersion) -> LearningPath? = { null }) {
    private val disk = AtomicSnapshot(file, SpaceState.serializer(), ::SpaceState)
    private val syncLock = File(file.path + ".sync").mutex()
    private fun id() = UUID.randomUUID().toString()
    private fun creation(id: String, title: String) = SavedSpace(id, edits = listOf(SpaceEdit(EditKind.CREATE, this.id(), title = title)))
    private fun checkTitle(title: String) { require(title.isNotBlank() && title.length <= 120 && title.none(Char::isISOControl)) }

    private fun migratedId(source: ContentVersion, member: SpaceMember): String = "source-" + when (member) {
        is SpaceMember.ServerDocumentRef -> stableId("server", source.contentId, source.contentVersion)
        is SpaceMember.LocalPackRef -> stableId("local", member.packageId, source.contentId, source.contentVersion)
    }

    private fun migrationKey(source: ContentVersion, member: SpaceMember) = stableId(source.contentId, source.contentVersion,
        if (member is SpaceMember.LocalPackRef) "pack" else "document", member.version().contentId, member.version().contentVersion)

    private fun SavedSpace.reconcile(aliases: Map<SpaceMember, SpaceMember>): SavedSpace {
        if (aliases.isEmpty()) return this
        val actual = view().members
        val desired = view(aliases).members
        val retire = actual - desired.toSet()
        // Removing an alias also removes its logical document when pending edits are replayed.
        // Reassert that document last if it is still wanted, even if the server already lists it.
        val retiredDocuments = retire.mapNotNull(aliases::get).toSet()
        val add = desired.filter { it !in actual || it in retiredDocuments }
        if (retire.isEmpty() && add.isEmpty()) return this
        // The existing head may already have reached the server. Keep its request ID and payload intact.
        return copy(edits = edits + retire.map { SpaceEdit(EditKind.REMOVE, id(), member = it, verificationFor = aliases[it] ?: it) } +
            add.map { SpaceEdit(EditKind.ADD, id(), member = it, verificationFor = it) })
    }

    suspend fun list(): List<Space> = list(materials())

    internal suspend fun list(catalog: List<Material>): List<Space> {
        val aliases = catalog.verifiedAliases()
        return disk.update { prior ->
            val entries = prior.entries.mapValues { it.value.reconcile(aliases) }.toMutableMap()
            val migrated = prior.migrated.toMutableSet()
            // A migrated relationship may have been removed or moved. Its verified identity is
            // already accounted for, including when the owned tutor appears first in the catalogue.
            for (material in catalog) {
                val local = material.localPack ?: continue
                if (local in aliases && migrationKey(material.source, local) in prior.migrated)
                    migrated += migrationKey(material.source, material.member)
            }
            for (material in catalog) {
                val source = material.source
                val member = material.member
                if (!migrated.add(migrationKey(source, member))) continue
                if (entries.values.any { member in it.view().members }) continue
                val id = migratedId(source, member)
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
    suspend fun add(id: String, member: SpaceMember): Space {
        val aliases = materials().verifiedAliases()
        val canonical = aliases[member] ?: member
        return edit(id) { entry ->
            val saved = entry.reconcile(aliases)
            val members = saved.view().members
            if (canonical in members) saved else {
                require(members.size < 128)
                saved.copy(edits = saved.edits + SpaceEdit(EditKind.ADD, this.id(), member = canonical))
            }
        }.view()
    }
    suspend fun remove(id: String, member: SpaceMember): Space {
        val aliases = materials().verifiedAliases()
        val canonical = aliases[member] ?: member
        return edit(id) { entry ->
            val rejectedAdd = entry.edits.firstOrNull {
                it.kind == EditKind.ADD && it.rejection != null && (aliases[it.member] ?: it.member) == canonical
            }
            val remaining = if (rejectedAdd == null) entry.reconcile(aliases) else entry.withoutRejectedEdit(rejectedAdd, aliases)
            if (canonical !in remaining.view().members) remaining else
                remaining.copy(edits = remaining.edits + SpaceEdit(EditKind.REMOVE, this.id(), member = canonical))
        }.view()
    }

    /** Each request uses the last acknowledged revision. Lost acknowledgements replay the exact same body and request ID. */
    suspend fun sync(id: String): Space = syncLock.withLock {
        val service = requireNotNull(api)
        val aliases = materials().verifiedAliases()
        edit(id) { it.reconcile(aliases) }
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

    private fun SavedSpace.withoutRejectedEdit(op: SpaceEdit, aliases: Map<SpaceMember, SpaceMember>): SavedSpace {
        require(op.rejection != null && op.kind != EditKind.CREATE)
        val document = aliases[op.member] ?: edits.firstNotNullOfOrNull {
            if (it.member == op.member) it.verificationFor else null
        } ?: op.member
        // Edits behind a rejected head are unsent. Recompute its derived membership changes
        // from the remaining user edits, so withdrawing an ADD cannot leave a replacement ADD.
        val retained = edits.filterNot {
            it == op || (op.verificationFor == null && document != null && it.verificationFor == document)
        }
        return copy(edits = retained).reconcile(aliases)
    }

    suspend fun withdrawRejected(id: String, requestId: String): Space {
        val aliases = materials().verifiedAliases()
        return edit(id) { saved ->
            saved.withoutRejectedEdit(saved.edits.single { it.requestId == requestId }, aliases)
        }.view()
    }

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
        val aliases = materials().verifiedAliases()
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
            prior.copy(entries = entries.mapValues { it.value.reconcile(aliases) })
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
        val catalog = materials()
        return route(list(catalog).single { it.spaceId == id }, language, catalog)
    }

    internal suspend fun route(space: Space, language: String, catalog: List<Material>): LearningPath? {
        val id = space.spaceId
        val members = catalog.filter { it.member in space.members || it.localPack?.let(space.members::contains) == true }
        val generated = space.activePathVersion?.let { loadPath(it) }
        val includedDocs = members.map { it.source }.toSet() + space.members.filterIsInstance<SpaceMember.ServerDocumentRef>().map { it.version() }
        val generatedNodes = generated?.nodes.orEmpty().filterNot { n -> n.evidence.filterIsInstance<EvidenceRef.Pdf>()
            .any { ContentVersion(it.documentId, it.documentVersion) !in includedDocs } }
        val kept = generatedNodes.map { it.nodeId }.toSet()
        val pathLanguage = generated?.language ?: language
        val nodes = generatedNodes.map { it.bindTutor(members, pathLanguage).copy(prerequisites = it.prerequisites.filter(kept::contains)) }.toMutableList()
        val used = nodes.map { it.activity.key("") }.toMutableSet()
        for (node in members.flatMap { it.nodes }) if (used.add(node.activity.key(""))) nodes += node
        if (nodes.isEmpty()) return null
        return LearningPath(generated?.pathId ?: "local-$id", generated?.pathVersion ?: "local", id, space.title,
            pathLanguage, nodes, generated?.excludedMembers.orEmpty().filter { it in space.members })
    }
}
