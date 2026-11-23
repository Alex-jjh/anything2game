package com.anything2game.server.activity

import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import com.anything2game.server.api.Quotas
import com.anything2game.server.api.ApiException
import com.anything2game.server.store.*
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.util.UUID

/** A published path job records its activation and any later revocation. A file-store job rename
 * makes the new revision and its active pointer visible together. */
class SpaceService(private val store: Store, private val quotas: Quotas) {
    private fun publications(owner: String) = store.listJobsByOwner(owner).filter {
        it.generation?.input is GenerationInput.SpacePath && (it.state == CompileJobState.READY || it.generation.publishedSources != null)
    }.groupBy { (it.generation!!.input as GenerationInput.SpacePath).spaceId }

    private fun effective(stored: StoredSpace, paths: List<JobRecord>): Space {
        val latest = paths.maxByOrNull { (it.generation!!.input as GenerationInput.SpacePath).expectedRevision }
        val revocations = paths.count { it.state == CompileJobState.CANCELLED }
        return stored.space.copy(revision = stored.space.revision + paths.size + revocations, activePathVersion = latest?.generation?.result?.content)
    }

    fun list(owner: String) = store.withOwnerLock(owner) {
        val paths = publications(owner)
        store.listSpaces(owner).map { effective(it, paths[it.space.spaceId].orEmpty()) }.sortedBy { it.spaceId }
    }

    fun get(owner: String, id: String): Space = store.withOwnerLock(owner) {
        val stored = store.getSpace(id)?.takeIf { it.ownerId == owner } ?: throw ApiException.notFound()
        effective(stored, publications(owner)[id].orEmpty())
    }

    fun create(owner: String, req: CreateSpaceRequest): Space = store.withOwnerLock(owner) {
        requestId(req.clientRequestId)
        title(req.title)
        val id = "sp_" + digest("$owner\u0000${req.clientRequestId}").take(40)
        val hash = digest(ContractJson.json.encodeToString(req))
        val existing = store.getSpace(id)
        if (existing != null) {
            if (existing.ownerId != owner || existing.receipts["create"] != hash) throw conflict("REQUEST_CONFLICT")
            return@withOwnerLock get(owner, id)
        }
        val space = Space(id, req.title.trim(), 1)
        store.saveSpace(StoredSpace(owner, space, mapOf("create" to hash)))
        space
    }

    fun rename(owner: String, id: String, req: RenameSpaceRequest) =
        edit(owner, id, "rename", req.clientRequestId, req.expectedRevision, ContractJson.json.encodeToString(req)) {
            title(req.title)
            it.copy(title = req.title.trim())
        }

    fun member(owner: String, id: String, req: SpaceMemberRequest, add: Boolean) =
        edit(owner, id, if (add) "add" else "remove", req.clientRequestId, req.expectedRevision, ContractJson.json.encodeToString(req)) { space ->
            val member = req.member
            val key = member.version()
            if (listOf(key.contentId, key.contentVersion).any { value -> value.isBlank() || value.length > 256 || value.any(Char::isISOControl) })
                throw ApiException.badRequest("Invalid material identifier or version.")
            if (add && member is SpaceMember.ServerDocumentRef) {
                checkServerId(member.documentId)
                val doc = store.getDocument(member.documentId)?.takeIf { it.ownerId == owner } ?: throw ApiException.notFound()
                if (doc.documentVersion != member.documentVersion) throw conflict("DOCUMENT_VERSION_CHANGED")
            }
            val members = if (add) (space.members + member).distinct() else space.members - member
            if (members.size > 128) throw ApiException.badRequest("A Space supports up to 128 materials.")
            space.copy(members = members)
        }

    private fun edit(owner: String, id: String, op: String, key: String, expected: Long, body: String, change: (Space) -> Space): Space =
        store.withOwnerLock(owner) {
            requestId(key)
            val raw = store.getSpace(id)?.takeIf { it.ownerId == owner } ?: throw ApiException.notFound()
            val prior = effective(raw, publications(owner)[id].orEmpty())
            val receiptKey = "$op:$key"
            if (store.listSpaces(owner).any { it.space.spaceId != id && receiptKey in it.receipts }) throw conflict("REQUEST_CONFLICT")
            val hash = digest(body)
            raw.receipts[receiptKey]?.let {
                if (it != hash) throw conflict("REQUEST_CONFLICT")
                return@withOwnerLock prior
            }
            if (prior.revision != expected) throw conflict("SPACE_STALE")
            val updated = change(raw.space)
            val delta = if (updated == raw.space) 0 else 1
            store.saveSpace(raw.copy(space = updated.copy(revision = raw.space.revision + delta), receipts = raw.receipts + (receiptKey to hash)))
            updated.copy(revision = prior.revision + delta, activePathVersion = prior.activePathVersion)
        }

    fun submit(owner: String, req: GenerationRequest): JobRecord = store.withOwnerLock(owner) {
        requestId(req.clientRequestId)
        val input = req.input
        store.listJobsByOwner(owner).firstOrNull { it.operation == input.operation && it.clientRequestId == req.clientRequestId }?.let {
            if (it.generation?.input != input) throw conflict("REQUEST_CONFLICT")
            return@withOwnerLock it
        }
        val language = when (input) {
            is GenerationInput.ConceptGame -> {
                if (input.topic.isBlank() || input.topic.length > 200 || input.topic.any(Char::isISOControl)) throw ApiException.badRequest("Enter a topic of 1 to 200 characters.")
                input.language
            }
            is GenerationInput.SpacePath -> {
                checkServerId(input.spaceId)
                if (get(owner, input.spaceId).revision != input.expectedRevision) throw conflict("SPACE_STALE")
                input.language
            }
        }
        if (language !in setOf("en", "zh")) throw ApiException.badRequest("Language must be en or zh.")
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val contentId = if (input is GenerationInput.SpacePath) "path_${input.spaceId}" else "game_$id"
        quotas.admitCompile(JobRecord("job_$id", owner, null, null, null, req.clientRequestId, contentId,
            createdAt = now, updatedAt = now, generation = GenerationWork(input)))
    }

    companion object {
        fun conflict(code: String) = ApiException(HttpStatusCode.Conflict, code, when (code) {
            "SPACE_STALE" -> "The Space has changed. Reload it and try again."
            "DOCUMENT_VERSION_CHANGED" -> "The document version has changed."
            else -> "This request ID was used for a different request."
        })
        fun checkServerId(id: String) {
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) throw ApiException.notFound()
        }
        private fun requestId(id: String) {
            if (id.isBlank() || id.length > 100 || id.any(Char::isISOControl)) throw ApiException.badRequest("Invalid clientRequestId.")
        }
        private fun title(value: String) {
            if (value.isBlank() || value.length > 120 || value.any(Char::isISOControl)) throw ApiException.badRequest("Enter a title of 1 to 120 characters.")
        }
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
