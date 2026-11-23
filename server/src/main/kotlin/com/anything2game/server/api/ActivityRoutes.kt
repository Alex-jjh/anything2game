package com.anything2game.server.api

import com.anything2game.contract.CompileJobState
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import com.anything2game.server.AppContext
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.*
import io.ktor.server.routing.*

internal fun Route.activityRoutes(ctx: AppContext) {
    val max = ctx.config.limits.maxSmallJsonBytes
    fun ownedJob(call: ApplicationCall) = ctx.store.getJob(call.pathId("jobId"))?.takeIf {
        it.ownerId == call.principal.ownerId && it.generation != null
    } ?: throw ApiException.notFound()

    get("/spaces") { call.respond(ctx.spaces.list(call.principal.ownerId)) }
    post("/spaces") {
        val request = call.receiveJson(CreateSpaceRequest.serializer(), max, strict = true)
        call.respond(HttpStatusCode.Created, ctx.spaces.create(call.principal.ownerId, request))
    }
    get("/spaces/{spaceId}") { call.respond(ctx.spaces.get(call.principal.ownerId, call.pathId("spaceId"))) }
    patch("/spaces/{spaceId}") {
        val request = call.receiveJson(RenameSpaceRequest.serializer(), max, strict = true)
        call.respond(ctx.spaces.rename(call.principal.ownerId, call.pathId("spaceId"), request))
    }
    suspend fun member(call: ApplicationCall, add: Boolean) {
        val request = call.receiveJson(SpaceMemberRequest.serializer(), max, strict = true)
        call.respond(ctx.spaces.member(call.principal.ownerId, call.pathId("spaceId"), request, add))
    }
    put("/spaces/{spaceId}/members") { member(call, true) }
    delete("/spaces/{spaceId}/members") { member(call, false) }
    post("/generation-jobs") {
        val owner = call.principal.ownerId
        val req = call.receiveJson(GenerationRequest.serializer(), max, strict = true)
        val job = ctx.spaces.submit(owner, req) { ctx.quotas.consumeCompile(owner) }
        ctx.runner.wake()
        call.respond(HttpStatusCode.Accepted, job.generationStatus())
    }
    get("/generation-jobs/{jobId}") { call.respond(ownedJob(call).generationStatus()) }
    post("/generation-jobs/{jobId}/cancel") {
        val job = ctx.runner.cancel(ownedJob(call).jobId) ?: throw ApiException.notFound()
        call.respond(job.generationStatus())
    }
    get("/content/{contentId}/versions/{version}") {
        val id = call.pathId("contentId")
        val version = call.pathId("version")
        val job = ctx.store.getJob(version)?.takeIf { it.ownerId == call.principal.ownerId &&
            it.state == CompileJobState.READY && it.generation?.result?.content == ContentVersion(id, version) } ?: throw ApiException.notFound()
        val bytes = ctx.store.readArtifact(job.jobId) ?: throw ApiException.notFound()
        call.response.header(HttpHeaders.CacheControl, "private, immutable")
        call.respondBytes(bytes, ContentType.parse("application/vnd.anything2game.pack+zip"))
    }
}
