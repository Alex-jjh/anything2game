package com.anything2game.app.data.learning

import com.anything2game.app.data.*
import com.anything2game.app.data.api.ActivityApi
import com.anything2game.app.data.api.ApiException
import com.anything2game.contract.ApiError
import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.ActivityPayload
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.contract.space.PATH_SCHEMA
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

@Serializable
data class GenerationTicket(val request: GenerationRequest, val status: GenerationStatus? = null,
    val cancelRequested: Boolean = false, val installed: Boolean = false, val submissionStarted: Boolean = false,
    val admissionError: ApiError? = null) {
    val id get() = request.clientRequestId
    val state get() = when {
        admissionError != null -> CompileJobState.FAILED
        cancelRequested && !submissionStarted && status == null -> CompileJobState.CANCELLED
        else -> status?.state
    }
}

/** Foreground polling is driven by the caller. The saved request survives a lost submit response. */
class GenerationRepository(private val file: File, private val api: ActivityApi, private val content: ContentLibrary,
    private val catalog: MaterialCatalog, private val spaces: SpaceRepository) {
    private val jobs = AtomicSnapshot(file, ListSerializer(GenerationTicket.serializer()), ::emptyList)
    suspend fun list() = jobs.read()
    suspend fun enqueue(input: GenerationInput): GenerationTicket {
        val ticket = GenerationTicket(GenerationRequest(input, UUID.randomUUID().toString()))
        jobs.update { it + ticket }
        return ticket
    }
    private fun lock(id: String) = File(file.parentFile, "job-${stableId(id)}").mutex()
    private suspend fun get(id: String) = list().single { it.id == id }
    private suspend fun save(ticket: GenerationTicket): GenerationTicket {
        jobs.update { tickets -> tickets.map { if (it.id == ticket.id) ticket else it } }
        return ticket
    }

    suspend fun refresh(id: String): GenerationTicket = lock(id).withLock {
        var ticket = get(id)
        if (ticket.state?.isTerminal == true) return@withLock ticket
        if (!ticket.submissionStarted) ticket = save(ticket.copy(submissionStarted = true))
        val status = ticket.status ?: try {
            api.submit(ticket.request)
        } catch (e: ApiException) {
            if (!e.definiteRejection) throw e
            return@withLock save(ticket.copy(admissionError = e.error()))
        }
        val next = when {
            status.state.isTerminal -> status
            ticket.cancelRequested -> api.cancel(status.jobId)
            ticket.status != null -> api.status(status.jobId)
            else -> status
        }
        save(ticket.copy(status = next))
    }
    suspend fun cancel(id: String): GenerationTicket {
        lock(id).withLock {
            val ticket = get(id)
            if (ticket.state?.isTerminal != true) save(ticket.copy(cancelRequested = true))
        }
        return refresh(id)
    }
    suspend fun retry(id: String): GenerationTicket {
        val original = get(id)
        require(original.state in setOf(CompileJobState.FAILED, CompileJobState.CANCELLED))
        val input = original.request.input
        val next = if (input is GenerationInput.SpacePath) input.copy(expectedRevision = api.space(input.spaceId).revision) else input
        return enqueue(next)
    }

    suspend fun download(id: String): GenerationTicket = lock(id).withLock {
        val ticket = get(id)
        val status = requireNotNull(ticket.status)
        require(status.state == CompileJobState.READY)
        val result = requireNotNull(status.result)
        install(result, ticket.request.input, status)
        save(ticket.copy(installed = true))
    }

    /** Fetch an already published route after a refresh, including a route generated on another device. */
    suspend fun downloadActivePath(localSpaceId: String): ContentVersion {
        val space = spaces.sync(localSpaceId)
        val version = requireNotNull(space.activePathVersion) { "This Space has no generated path" }
        install(GeneratedContent(version, ContentKind.PATH), GenerationInput.SpacePath(space.spaceId, space.revision), null)
        return version
    }

    private suspend fun install(result: GeneratedContent, input: GenerationInput, status: GenerationStatus?) {
        val bytes = api.archive(result.content)
        val declared = com.anything2game.contract.pack.PackCodec.readManifest(bytes.inputStream()).getOrThrow()
        require(declared.origin == PackOrigin.SERVER)
        if (status != null) require(declared.fixture == status.fixture && declared.modelId == status.modelId)
        require(declared.packageSchema == if (result.kind == ContentKind.GAME) GAME_SCHEMA else PATH_SCHEMA)
        content.install(bytes.inputStream(), catalog.availableDocuments(), result.content) { pack ->
            when (val payload = pack.payload) {
                is ActivityPayload.Path -> {
                    require(input is GenerationInput.SpacePath && payload.path.spaceId == input.spaceId)
                    if (status != null) require(payload.path.language == input.language)
                    catalog.verify(payload.path)
                }
                is ActivityPayload.Game -> require(input is GenerationInput.ConceptGame && payload.game.language == input.language)
            }
        }
        if (result.kind == ContentKind.PATH) spaces.activate(result.content, requireNotNull(content.path(result.content)))
    }
}
