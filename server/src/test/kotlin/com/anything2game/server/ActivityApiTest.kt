package com.anything2game.server

import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

internal suspend fun Api.submitActivity(token: String, input: GenerationInput, key: String) =
    send(HttpMethod.Post, "/v1/generation-jobs", token, ContractJson.json.encodeToString(GenerationRequest(input, key)))

internal suspend fun Api.waitActivity(token: String, id: String): GenerationStatus {
    val deadline = System.currentTimeMillis() + 10_000
    while (true) {
        val status = decode(GenerationStatus.serializer(), send(HttpMethod.Get, "/v1/generation-jobs/$id", token))
        if (status.state.isTerminal) return status
        check(System.currentTimeMillis() < deadline)
        withContext(Dispatchers.Default) { delay(15) }
    }
}

class ActivityApiTest {
    @Test fun `space edits survive replay enforce owner and revision and accept opaque local identities`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val (_, stranger) = api.install()
            val body = ContractJson.json.encodeToString(CreateSpaceRequest("Seminar", "create"))
            suspend fun create() = api.send(HttpMethod.Post, "/v1/spaces", token, body)
            val first = create()
            assertEquals(HttpStatusCode.Created, first.status)
            val space = api.decode(Space.serializer(), first)
            assertEquals(space, api.decode(Space.serializer(), create()))
            val path = "/v1/spaces/${space.spaceId}"
            assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, path, stranger).status)
            val local = SpaceMember.LocalPackRef("旧书", "版本:1")
            val edit = ContractJson.json.encodeToString(SpaceMemberRequest(local, space.revision, "local"))
            suspend fun add(body: String) = api.send(HttpMethod.Put, "$path/members", token, body)
            val changed = api.decode(Space.serializer(), add(edit))
            assertEquals(listOf(local), changed.members)
            assertEquals(changed, api.decode(Space.serializer(), add(edit)))
            assertEquals(HttpStatusCode.Conflict, add(edit.replace("版本:1", "版本:2")).status)
            assertEquals(HttpStatusCode.Conflict, add(edit.replace("\"local\"", "\"stale\"")).status)
            val second = api.decode(Space.serializer(), api.send(HttpMethod.Post, "/v1/spaces", token,
                ContractJson.json.encodeToString(CreateSpaceRequest("Another", "another"))))
            val reused = api.send(HttpMethod.Put, "/v1/spaces/${second.spaceId}/members", token, edit)
            assertEquals(HttpStatusCode.Conflict, reused.status)
            val ownedDoc = api.document(token, listOf("A versioned note for this reader."))
            val wrong = SpaceMember.ServerDocumentRef(ownedDoc.documentId, "unknown")
            assertEquals(HttpStatusCode.Conflict, add(ContractJson.json.encodeToString(SpaceMemberRequest(wrong, changed.revision, "wrong"))).status)
            val otherDoc = api.document(stranger, listOf("A private note for the owner."))
            val inaccessible = SpaceMember.ServerDocumentRef(otherDoc.documentId, otherDoc.documentVersion)
            assertEquals(HttpStatusCode.NotFound, add(ContractJson.json.encodeToString(SpaceMemberRequest(inaccessible, changed.revision, "private"))).status)
            val renamed = api.decode(Space.serializer(), api.send(HttpMethod.Patch, path, token,
                ContractJson.json.encodeToString(RenameSpaceRequest("New title", changed.revision, "name"))))
            val removed = api.decode(Space.serializer(), api.send(HttpMethod.Delete, "$path/members", token,
                ContractJson.json.encodeToString(SpaceMemberRequest(local, renamed.revision, "remove"))))
            assertEquals("New title", removed.title)
            assertTrue(removed.members.isEmpty())
        }
    }

    @Test fun `concept jobs publish a retrievable owner scoped archive and dedupe bodies`() = TestEnv().use { env ->
        env.run {
            val api = api()
            val (_, token) = api.install()
            val (_, other) = api.install()
            val input = GenerationInput.ConceptGame("binary search")
            val response = api.submitActivity(token, input, "one")
            assertEquals(HttpStatusCode.Accepted, response.status)
            val id = api.decode(GenerationStatus.serializer(), response).jobId
            assertEquals(id, api.decode(GenerationStatus.serializer(), api.submitActivity(token, input, "one")).jobId)
            assertEquals(HttpStatusCode.Conflict, api.submitActivity(token, input.copy(topic = "opportunity cost"), "one").status)
            assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, "/v1/compile-jobs/$id", token).status)
            assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, "/v1/generation-jobs/$id", other).status)
            val done = api.waitActivity(token, id)
            assertEquals(done.error.toString(), CompileJobState.READY, done.state)
            val content = done.result!!.content
            val url = "/v1/content/${content.contentId}/versions/${content.contentVersion}"
            val bytes = api.send(HttpMethod.Get, url, token).bodyAsBytes()
            val blob = PackCodec.readActivity(bytes.inputStream(), emptySet(), validateGame = com.anything2game.engine.game.GameRuntime::validate).getOrThrow()
            assertTrue(blob.manifest.fixture)
            assertEquals("fake-llm", blob.manifest.modelId)
            val game = (blob.payload as ActivityPayload.Game).game
            assertEquals(content.contentId, game.gameId)
            assertEquals("binary-search", game.conceptRefs.single())
            assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, url, other).status)
            val unsupported = api.decode(GenerationStatus.serializer(), api.submitActivity(token, GenerationInput.ConceptGame("astronomy"), "two"))
            assertEquals("UNSUPPORTED_TOPIC", api.waitActivity(token, unsupported.jobId).error!!.code)
        }
    }

    @Test fun `undeclared behavior or fabricated reviewed sources cannot become downloadable`() {
        for ((original, bad) in listOf("\"schema\"" to "\"script\":\"run\",\"schema\"", "https://xlinux.nist.gov" to "https://invalid.example",
            "\"candidates\":12" to "\"candidates\":16", "\"queryBudget\":4" to "\"queryBudget\":1")) {
            val model = RecordingLlm { req, _, out -> if (req.label.endsWith("game-spec")) out.replace(original, bad) else out }
            TestEnv(llm = model).use { env -> env.run {
                val api = api()
                val token = api.install().second
                val response = api.submitActivity(token, GenerationInput.ConceptGame("binary search"), "bad")
                assertEquals(HttpStatusCode.Accepted, response.status)
                val done = api.waitActivity(token, api.decode(GenerationStatus.serializer(), response).jobId)
                assertEquals(CompileJobState.FAILED, done.state)
                assertNull(done.result)
            } }
        }
    }

    @Test fun `path citations distinguish matching chunk IDs and exclude unavailable or local artifacts`() = TestEnv(llm = RecordingLlm()).use { env ->
        env.run {
            val api = api()
            val token = api.install().second
            var space = api.decode(Space.serializer(), api.send(HttpMethod.Post, "/v1/spaces", token,
                ContractJson.json.encodeToString(CreateSpaceRequest("Study", "space"))))
            suspend fun add(member: SpaceMember) {
                val req = SpaceMemberRequest(member, space.revision, "add-${space.revision}")
                space = api.decode(Space.serializer(), api.send(HttpMethod.Put, "/v1/spaces/${space.spaceId}/members", token, ContractJson.json.encodeToString(req)))
            }
            val docs = listOf(Examples.retrieval, Examples.courtyardZh).map { api.compiled(token, pagesOf(it)) }
            docs.forEach { (doc, _, _) -> add(SpaceMember.ServerDocumentRef(doc.documentId, doc.documentVersion)) }
            val bare = api.document(token, listOf("Uploaded text without any generated lesson."))
            val unavailable = SpaceMember.ServerDocumentRef(bare.documentId, bare.documentVersion)
            val local = SpaceMember.LocalPackRef("local-private", "old")
            add(unavailable)
            add(local)
            val started = api.decode(GenerationStatus.serializer(), api.submitActivity(token, GenerationInput.SpacePath(space.spaceId, space.revision), "path"))
            val done = api.waitActivity(token, started.jobId)
            assertEquals(done.error.toString(), CompileJobState.READY, done.state)
            assertEquals(setOf(unavailable, local), done.excludedMembers.toSet())
            val content = done.result!!.content
            val bytes = api.send(HttpMethod.Get, "/v1/content/${content.contentId}/versions/${content.contentVersion}", token).bodyAsBytes()
            val deps = docs.map { it.third!!.let { p -> ContentVersion(p.packageId, p.contentVersion) } }.toSet()
            val pack = PackCodec.readActivity(bytes.inputStream(), deps, validateGame = com.anything2game.engine.game.GameRuntime::validate).getOrThrow()
            val path = (pack.payload as ActivityPayload.Path).path
            assertTrue((env.llm as RecordingLlm).requests.filter { it.label == "space-path" || it.label == "activity-experience" }
                .none { "local-private" in it.user || bare.documentId in it.user })
            assertEquals(deps, path.dependencies)
            val refs = path.nodes.flatMap { it.evidence }.filterIsInstance<EvidenceRef.Pdf>()
            assertEquals(docs.map { it.first.documentId }.toSet(), refs.map { it.documentId }.toSet())
            refs.forEach { ref ->
                val pkg = docs.single { it.first.documentId == ref.documentId }.third!!
                assertEquals(pkg.documentVersion, ref.documentVersion)
                assertTrue(pkg.chunk(ref.chunkId)!!.text.contains(ref.quote))
            }
            val current = api.decode(Space.serializer(), api.send(HttpMethod.Get, "/v1/spaces/${space.spaceId}", token))
            assertEquals(content, current.activePathVersion)
            val nextJob = api.decode(GenerationStatus.serializer(), api.submitActivity(token, GenerationInput.SpacePath(space.spaceId, current.revision), "next"))
            val next = api.waitActivity(token, nextJob.jobId).result!!.content
            assertEquals(content.contentId, next.contentId)
            assertNotEquals(content.contentVersion, next.contentVersion)
            assertArrayEquals(bytes, api.send(HttpMethod.Get, "/v1/content/${content.contentId}/versions/${content.contentVersion}", token).bodyAsBytes())
        }
    }
}
