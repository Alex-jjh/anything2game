package com.anything2game.app.data

import android.net.Uri
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.api.*
import com.anything2game.app.data.content.*
import com.anything2game.app.data.learning.*
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.tutor.*
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import com.anything2game.contract.tutor.*
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.session.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class LearningIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private val json = ContractJson.json

    @Test fun `unsubmitted cancellation works without a connection or an install token`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val api = ActivityApi(ApiTransport(app, "", "no-connection"))
        val jobs = GenerationRepository(File(tmp.newFolder(), "jobs"), api, deps.content, deps.materials, deps.spaces)
        val request = jobs.enqueue(GenerationInput.ConceptGame("二分查找", "zh"))
        val cancelled = jobs.cancel(request.id)
        assertTrue(cancelled.cancelRequested)
        assertNull(cancelled.status)
        assertNotEquals(request.id, jobs.retry(request.id).id)
    }

    @Test fun `a lost membership acknowledgement replays the same request and never uploads the local file`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val member = SpaceMember.LocalPackRef("甲/乙", "1:旧")
        var remote = Space("remote", "Reading", 1)
        val attempts = mutableListOf<SpaceMemberRequest>()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            var code = 200
            val body = when {
                path == "/v1/installs" -> json.encodeToString(InstallResponse("test", "sample-token"))
                exchange.requestHeaders.getFirst("Authorization") != "Bearer sample-token" -> { code = 401; "{}" }
                path == "/v1/spaces" && method == "POST" -> json.encodeToString(remote)
                path == "/v1/spaces/remote/members" && method == "PUT" -> {
                    attempts += json.decodeFromString<SpaceMemberRequest>(exchange.requestBody.bufferedReader().readText())
                    remote = remote.copy(revision = 2, members = listOf(member))
                    if (attempts.size == 1) { code = 503; "{}" } else json.encodeToString(remote)
                }
                path == "/v1/spaces/remote" && method == "GET" -> json.encodeToString(remote)
                else -> { code = 404; "{}" }
            }.toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = ActivityApi(ApiTransport(app, "http://127.0.0.1:${server.address.port}", "test-space"))
            val file = File(tmp.newFolder(), "spaces")
            fun reopen() = SpaceRepository(file, { emptyList() }, api)
            val space = reopen().create("Reading")
            reopen().add(space.spaceId, member)
            assertEquals("HTTP_503", (runCatching { reopen().sync(space.spaceId) }.exceptionOrNull() as ApiException).code)
            assertEquals(listOf(member), reopen().sync(space.spaceId).members)
            assertEquals(2, attempts.size)
            assertEquals(attempts[0], attempts[1])
            assertEquals(member, attempts[0].member)
            assertEquals(1L, attempts[0].expectedRevision)
        } finally { server.stop(0) }
    }

    @Test fun `generated route installs against tutor document identity and keeps every local node after restart`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val material = deps.materials.materials().first { it.tutorKey != null && it.nodes.size >= 2 }
        val first = material.nodes.first { it.activity is ActivityRef.Tutor }
        val version = ContentVersion("path-example", "1")
        val path = LearningPath(version.contentId, version.contentVersion, "remote", "Generated route", "en",
            listOf(first.copy(nodeId = "remote-node")), listOf(material.member))
        var published = version
        fun archive(nodes: List<PathNode>) = PackCodec.build(path.copy(pathVersion = published.contentVersion, nodes = nodes), PackOrigin.SERVER).let {
            PackCodec.toBytes(it.copy(manifest = it.manifest.copy(modelId = "fake-llm", fixture = true)))
        }
        val brokenRef = (first.activity as ActivityRef.Tutor).copy(activityId = "nonexistent-concept")
        var bytes = archive(listOf(first.copy(activity = brokenRef)))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var remote = Space("remote", "Collection", 1)
        server.createContext("/") { exchange ->
            val url = exchange.requestURI.path
            val body = when {
                url == "/v1/installs" -> json.encodeToString(InstallResponse("test", "sample-token")).toByteArray()
                url == "/v1/spaces" -> json.encodeToString(remote).toByteArray()
                url.endsWith("/members") -> {
                    remote = remote.copy(revision = 2, members = listOf(material.member))
                    json.encodeToString(remote).toByteArray()
                }
                url == "/v1/spaces/remote" -> json.encodeToString(remote).toByteArray()
                url == "/v1/generation-jobs" -> {
                    remote = remote.copy(revision = remote.revision + 1, activePathVersion = published)
                    json.encodeToString(GenerationStatus("example-job", CompileJobState.READY,
                        result = GeneratedContent(published, ContentKind.PATH), modelId = "fake-llm", fixture = true)).toByteArray()
                }
                url == "/v1/content/path-example/versions/${published.contentVersion}" -> bytes
                else -> error(url)
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val dir = tmp.newFolder()
            val api = ActivityApi(ApiTransport(app, "http://127.0.0.1:${server.address.port}", "learning-path-test"))
            val spaces = SpaceRepository(File(dir, "spaces"), { listOf(material) }, api, deps.content::path)
            val space = spaces.list().single()
            val request = spaces.pathRequest(space.spaceId, "en")
            fun reopen() = GenerationRepository(File(dir, "jobs"), api, deps.content, deps.materials, spaces)
            val ticket = reopen().enqueue(request)
            assertEquals(CompileJobState.READY, reopen().refresh(ticket.id).state)
            assertTrue("a version match alone cannot make a missing tutor concept runnable", runCatching { reopen().download(ticket.id) }.isFailure)
            assertNull(deps.content.path(version))
            bytes = archive(path.nodes)
            assertTrue(reopen().download(ticket.id).installed)
            val manifest = deps.content.manifests().single { it.packageId == version.contentId }
            assertTrue(manifest.fixture)
            assertEquals("fake-llm", manifest.modelId)
            published = version.copy(contentVersion = "2")
            val game = ReviewedGames.partition("en")
            val practiceStage = ActivityRef.Game(game.gameId, game.gameVersion, game.stages.first().stageId, game.stages.first().rulesVersion)
            bytes = archive(listOf(first.copy(activity = practiceStage)))
            val replacement = reopen().enqueue(spaces.pathRequest(space.spaceId, "en"))
            assertEquals(CompileJobState.READY, reopen().refresh(replacement.id).state)
            assertTrue(runCatching { reopen().download(replacement.id) }.isFailure)
            assertNull(deps.content.path(published))
            assertEquals(version, spaces.list().single().activePathVersion)
            val offline = SpaceRepository(File(dir, "spaces"), { listOf(material) }, loadPath = deps.content::path)
            val route = requireNotNull(offline.route(space.spaceId, "en"))
            assertEquals("remote-node", route.nodes.first().nodeId)
            assertEquals(material.nodes.map { it.activity }.toSet(), route.nodes.map { it.activity }.toSet())
            assertEquals(material.nodes.size, route.nodes.size)
            assertEquals(listOf(material.member), route.excludedMembers)
        } finally { server.stop(0) }
    }

    @Test fun `unversioned mastery stays historical and opaque versions and Chinese IDs never collide`() = runBlocking {
        val dir = tmp.newFolder()
        val old = MasteryRecord("old", box = 3, attempts = 4)
        val raw = SessionCodec.encodeMastery(listOf(old))
        File(dir, "p.json").writeText(raw)
        val storage = FileMasteryStore(dir)
        assertEquals(old, storage.legacyUnversioned("p")["old"])
        assertTrue(storage.all("p", "1").isEmpty())
        storage.put("甲", "1:2", MasteryRecord("alpha", attempts = 1))
        storage.put("乙", "1/2", MasteryRecord("beta", attempts = 3))
        assertEquals(setOf("alpha"), FileMasteryStore(dir).all("甲", "1:2").keys)
        assertTrue(storage.all("甲", "1/2").isEmpty())
        assertEquals(raw, File(dir, "p.json").readText())
    }

    @Test fun `successful queue item persists even when a sibling PDF is invalid`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val seed = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session
        val repo = object : TutorRepository by deps.tutors {
            override suspend fun upload(request: ImportRequest): DocumentResponse {
                val staged = File(requireNotNull((request as ImportRequest.PdfFile).uri.path))
                require(staged.readText() == "%PDF-approved-fixture")
                return DocumentResponse("imported", "sha256:one", 1, request.title)
            }
            override suspend fun create(document: DocumentResponse, requestId: String, language: String) =
                seed.copy(sessionId = "tut_" + requestId, mode = TutorMode.MOCK, documentId = document.documentId, documentVersion = document.documentVersion)
        }
        val dir = tmp.newFolder()
        val spaces = SpaceRepository(File(dir, "spaces"), { emptyList() })
        val space = spaces.create("Class")
        fun reopen() = PdfImportQueue(File(dir, "queue"), { if (it.title == "good") "%PDF-approved-fixture".toByteArray() else "bad".toByteArray() }, repo, spaces)
        val picks = listOf("good", "bad").map { ImportRequest.PdfFile(it, Uri.parse("content://tests/$it"), null) }
        val rows = reopen().enqueue(space.spaceId, picks)
        assertEquals("INVALID_PDF", rows[1].error)
        val done = reopen().process(rows[0].id)
        assertNotNull(done.sessionId)
        assertEquals("INVALID_PDF", reopen().process(rows[1].id).error)
        assertEquals(done, reopen().process(rows[0].id))
        assertEquals(listOf(SpaceMember.ServerDocumentRef("imported", "sha256:one")), spaces.list().single().members)
    }

    @Test fun `seeded practice and actual tutor replies project into the active profile only`() = runBlocking {
        val deps = AndroidAppContainer(app)
        deps.profiles.choose(LocalProfile.SAMPLE)
        val played = deps.learningProgress.projection()
        assertTrue(played.completed.any { it.contentId == "demo_mapreduce" })
        assertFalse(played.completed.any { it.contentId == "demo_resnet" })
        deps.profiles.choose(LocalProfile.GUEST)
        assertTrue(deps.learningProgress.projection().completed.isEmpty())
        val tutor = deps.tutors
        var cache = tutor.open(EXAMPLE_TUTOR_PACKAGE).cache
        cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER })
        cache = cache.copy(session = tutor.assess(cache, "diagnostic"))
        tutor.save(cache)
        assertTrue(deps.learningProgress.projection().completed.isEmpty())
        cache = cache.copy(session = tutor.turn(cache, TutorIntent.START, "", "first"))
        tutor.save(cache)
        tutor.save(cache)
        val progress = AndroidAppContainer(app).learningProgress.projection()
        assertEquals(cache.session.questions.first().conceptId, progress.completed.single().activityId)
        assertTrue(progress.mastery.isEmpty())
        assertEquals(1, progress.days.size)
    }
}
