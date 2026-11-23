package com.anything2game.app.data

import android.net.Uri
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.api.*
import com.anything2game.app.data.learning.*
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.tutor.*
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.play.*
import com.anything2game.contract.*
import com.anything2game.contract.game.GenerationInput
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import com.anything2game.contract.tutor.*
import com.anything2game.engine.demo.ScriptedPlayer
import com.anything2game.engine.session.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class LearningRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private val json = ContractJson.json
    private fun ready(vm: PlayViewModel, predicate: (PlayUiState.Ready) -> Boolean = { true }): PlayUiState.Ready {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end) {
            ShadowLooper.idleMainLooper()
            val state = vm.state.value
            if (state is PlayUiState.Ready && predicate(state)) return state
            if (state is PlayUiState.Failed) error(state.reason)
            Thread.sleep(5)
        }
        error("No ready state")
    }

    @Test fun `migrated Case resolves and resumes through Space and real player after restart`() {
        val before = AndroidAppContainer(app)
        val case = runBlocking {
            before.profiles.choose(LocalProfile.SAMPLE)
            requireNotNull(before.sessions.load("demo_resnet"))
        }
        val dir = File(LocalProfile.SAMPLE.dir(app), "sessions")
        val legacy = case.copy(attemptId = "")
        File(dir, "demo_resnet.json").writeText(SessionCodec.encode(legacy))
        require(dir.resolve("versioned").deleteRecursively())
        repeat(2) {
            val deps = AndroidAppContainer(app)
            val activity = runBlocking {
                val space = deps.spaces.list().single { s -> s.members.any { it.version().contentId == case.packageId } }
                val node = deps.spaces.route(space.spaceId, "zh")!!.nodes.first { it.activity is ActivityRef.Practice && it.activity.activityId == case.levelId }
                (deps.materials.resolve(node.activity) as OpenActivity.Practice).activity
            }
            val vm = PlayViewModel(deps, activity.contentId, activity.activityId, activity.frame, activity.contentVersion)
            val resumed = ready(vm).session
            assertEquals(FrameId.CASE, resumed.frameId)
            assertEquals(case.position, resumed.position)
            assertEquals(case.results, resumed.results)
            vm.stopListeningToContext()
        }
    }

    @Test fun `interrupted real answer recovers feedback and promotes its unit once`() {
        val deps = AndroidAppContainer(app)
        val pkg = runBlocking { deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow() }
        val level = pkg.levels.first().levelId
        val vm = PlayViewModel(deps, pkg.packageId, level, FrameId.CLASSIC)
        val initial = ready(vm)
        val change = Action.Submit(ScriptedPlayer.correctAnswer(initial.item!!))
        val folder = File(app.filesDir, "sessions/versioned")
        val originalPermissions = java.nio.file.Files.getPosixFilePermissions(folder.toPath())
        java.nio.file.Files.setPosixFilePermissions(folder.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
        vm.dispatch(change)
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end && vm.state.value !is PlayUiState.Failed) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        java.nio.file.Files.setPosixFilePermissions(folder.toPath(), originalPermissions)
        assertTrue(vm.state.value is PlayUiState.Failed)
        val intermediate = runBlocking { FileMasteryStore(File(app.filesDir, "mastery")).all(pkg.packageId, pkg.contentVersion) }
        assertEquals(1, intermediate.getValue(initial.unit!!.unitId).attempts)
        vm.stopListeningToContext()
        val reopened = AndroidAppContainer(app)
        val next = ready(PlayViewModel(reopened, pkg.packageId, level, FrameId.CLASSIC))
        assertTrue(next.session.phase is Phase.Feedback)
        assertEquals(1, next.session.results.size)
        val unit = initial.unit.unitId
        assertEquals(1, runBlocking { reopened.mastery.all(pkg.packageId, pkg.contentVersion).getValue(unit).attempts })
    }

    @Test fun `tutor credit recovers from interrupted ledger write using actual reordered explanation`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val repo = deps.tutors
        var cache = repo.open(EXAMPLE_TUTOR_PACKAGE).cache
        val script = deps.tutorScripts.scriptFor(EXAMPLE_TUTOR_PACKAGE)!!
        val first = script.blueprint.concepts.first()
        val draft = cache.session.questions.associate { it.id to if (it.id == first.question.id) first.correctOptionId else UNKNOWN_ANSWER }
        cache = cache.copy(draftAnswers = draft)
        cache = cache.copy(session = repo.assess(cache, "assessment"))
        repo.save(cache)
        val reply = repo.turn(cache, TutorIntent.START, "", "first-teaching-turn")
        val actual = TutorRules.orderedQuestions(reply)[reply.conceptPosition]
        assertNotEquals(reply.questions.first().conceptId, actual.conceptId)
        val ledger = File(app.filesDir, "learning.json")
        require(ledger.mkdir())
        assertTrue(runCatching { repo.save(cache.copy(session = reply)) }.isFailure)
        require(ledger.delete())
        val after = AndroidAppContainer(app)
        assertEquals(actual.conceptId, after.learningProgress.projection().completed.single().activityId)
        assertEquals(reply.messages, after.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session.messages)
        assertTrue(after.learningProgress.projection().mastery.isEmpty())
    }

    @Test fun `sideload sharing document labels stays separate from owned tutor and its server practice`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
        val tutor = deps.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_owned", mode = "mock")
        deps.tutors.save(TutorCache(tutor))
        val compiled = pkg.copy(packageId = "server-practice")
        val sideload = pkg.copy(packageId = "unrelated-pack", title = "Independent handout")
        deps.content.install(PackCodec.toBytes(PackCodec.build(compiled, emptyList(), PackOrigin.SERVER)).inputStream())
        deps.content.install(PackCodec.toBytes(PackCodec.build(sideload, emptyList(), PackOrigin.IMPORT)).inputStream())
        val mislabeled = pkg.copy(packageId = "different-source", sources = pkg.sources.map { it.copy(text = "Different source. " + it.text) })
        deps.content.install(PackCodec.toBytes(PackCodec.build(mislabeled, emptyList(), PackOrigin.SERVER)).inputStream())
        val spaces = deps.spaces.list()
        val remote = SpaceMember.ServerDocumentRef(tutor.documentId, tutor.documentVersion)
        val local = SpaceMember.LocalPackRef(sideload.packageId, sideload.contentVersion)
        val serverSpace = spaces.single { remote in it.members }
        assertFalse(local in serverSpace.members)
        val nodes = deps.spaces.route(serverSpace.spaceId, "en")!!.nodes
        assertTrue(nodes.any { it.activity.contentId == compiled.packageId })
        assertFalse(nodes.any { it.activity.contentId == sideload.packageId })
        assertFalse(nodes.any { it.activity.contentId == mislabeled.packageId })
    }

    private suspend fun server(handler: (String, String, String) -> Pair<Int, String>, use: suspend (ApiTransport) -> Unit) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val path = ex.requestURI.path
            val reply = if (path == "/v1/installs") 201 to json.encodeToString(InstallResponse("test", "sample"))
                else handler(path, ex.requestMethod, ex.requestBody.bufferedReader().readText())
            if (reply.first == -1) {
                ex.close() // Listener accepted the payload, but the connection closes before an HTTP acknowledgement.
                return@createContext
            }
            val bytes = reply.second.toByteArray()
            ex.sendResponseHeaders(reply.first, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            // Robolectric normally routes URL.openConnection through desktop Java, which refuses PATCH.
            // Select Android's own HTTP URL handler from android-all for the real socket exchange.
            val handler = Class.forName("com.android.okhttp.HttpHandler").getDeclaredConstructor().newInstance() as java.net.URLStreamHandler
            use(ApiTransport(app, "http://127.0.0.1:${http.address.port}", "review-qa") { url ->
                java.net.URL(null, url.toExternalForm(), handler).openConnection() as java.net.HttpURLConnection
            })
        } finally { http.stop(0) }
    }
    private fun errorResponse(status: Int, code: String) = status to json.encodeToString(ErrorBody(ApiError(code, code, false)))

    @Test fun `rejected job admission survives restart and retries only at refreshed revision`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val sent = mutableListOf<com.anything2game.contract.game.GenerationRequest>()
        server({ path, _, body -> when (path) {
            "/v1/generation-jobs" -> {
                val request = json.decodeFromString<com.anything2game.contract.game.GenerationRequest>(body)
                sent += request
                if ((request.input as GenerationInput.SpacePath).expectedRevision == 1L) errorResponse(409, "SPACE_STALE")
                else 202 to json.encodeToString(com.anything2game.contract.game.GenerationStatus("job", CompileJobState.QUEUED))
            }
            "/v1/spaces/owned" -> 200 to json.encodeToString(Space("owned", "new", 2))
            else -> errorResponse(404, "NOT_FOUND")
        } }) { transport ->
            val file = File(tmp.newFolder(), "jobs")
            fun reopen() = GenerationRepository(file, ActivityApi(transport), deps.content, deps.materials, deps.spaces)
            val original = reopen().enqueue(GenerationInput.SpacePath("owned", 1))
            runCatching { reopen().refresh(original.id) }
            assertEquals(CompileJobState.FAILED, reopen().list().single().state)
            reopen().cancel(original.id)
            assertEquals(1, sent.size)
            val retry = reopen().retry(original.id)
            assertEquals(CompileJobState.QUEUED, reopen().refresh(retry.id).state)
            assertNotEquals(sent[0].clientRequestId, sent[1].clientRequestId)
        }
    }

    @Test fun `removing rejected member unblocks later edits without resending deleted document`() = runBlocking {
        var remote = Space("remote", "Original", 1)
        var rejectedCalls = 0
        server({ path, method, body -> when {
            path == "/v1/spaces" || method == "GET" -> 200 to json.encodeToString(remote)
            method == "PUT" -> { rejectedCalls++; errorResponse(404, "NOT_FOUND") }
            method == "PATCH" -> {
                val r = json.decodeFromString<RenameSpaceRequest>(body)
                remote = remote.copy(title = r.title, revision = remote.revision + 1)
                200 to json.encodeToString(remote)
            }
            else -> errorResponse(400, "UNEXPECTED")
        } }) { transport ->
            val file = File(tmp.newFolder(), "spaces")
            fun reopen() = SpaceRepository(file, { emptyList() }, ActivityApi(transport))
            val id = reopen().create("Original").spaceId
            val member = SpaceMember.ServerDocumentRef("deleted", "1")
            reopen().add(id, member)
            reopen().rename(id, "Updated")
            assertTrue(runCatching { reopen().sync(id) }.isFailure)
            reopen().remove(id, member)
            assertEquals("Updated", reopen().sync(id).title)
            assertEquals(1, rejectedCalls)
        }
    }

    @Test fun `generation admission with an ambiguous response replays original body and can cancel the recovered job`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val bodies = mutableListOf<String>()
        server({ path, _, body -> when (path) {
            "/v1/generation-jobs" -> {
                bodies += body
                if (bodies.size == 1) errorResponse(503, "UNAVAILABLE")
                else 202 to json.encodeToString(com.anything2game.contract.game.GenerationStatus("same-job", CompileJobState.QUEUED))
            }
            "/v1/generation-jobs/same-job/cancel" ->
                200 to json.encodeToString(com.anything2game.contract.game.GenerationStatus("same-job", CompileJobState.CANCELLED))
            else -> errorResponse(404, "NOT_FOUND")
        } }) { transport ->
            val file = File(tmp.newFolder(), "jobs")
            fun reopen() = GenerationRepository(file, ActivityApi(transport), deps.content, deps.materials, deps.spaces)
            val ticket = reopen().enqueue(GenerationInput.ConceptGame("binary search"))
            assertTrue(runCatching { reopen().refresh(ticket.id) }.isFailure)
            assertNull(reopen().list().single().admissionError)
            assertEquals(CompileJobState.CANCELLED, reopen().cancel(ticket.id).state)
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
        }
    }

    @Test fun `enqueuing and processing the same staged PDF cannot lose its completed session`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val base = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondRead = CompletableDeferred<Unit>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val uploads = java.util.concurrent.atomic.AtomicInteger()
        server({ path, _, _ -> when (path) {
            "/v1/documents" -> {
                uploads.incrementAndGet()
                201 to json.encodeToString(DocumentResponse("batch-doc", "1", 1, "Good"))
            }
            "/v1/documents/batch-doc/tutor" -> 200 to json.encodeToString(base.copy(sessionId = "tut_batch", mode = "mock", documentId = "batch-doc", documentVersion = "1"))
            else -> errorResponse(404, "NOT_FOUND")
        } }) { transport ->
            val dir = tmp.newFolder()
            val space = deps.spaces.create("Selected")
            val real = AndroidTutorRepository(app, deps.packages, deps.tutorScripts, {}, File(dir,"tutors"), http = TutorHttpClient(app, transport))
            val queue = PdfImportQueue(File(dir, "queue"), {
                if (reads.incrementAndGet() == 1) { started.complete(Unit); release.await() } else secondRead.complete(Unit)
                "%PDF-good".toByteArray()
            }, real, deps.spaces)
            val enqueue = async(Dispatchers.Default) { queue.enqueue(space.spaceId, listOf(ImportRequest.PdfFile("Good", Uri.parse("content://selected/file"), null))) }
            started.await()
            val row = queue.list().single()
            val process = async(start = CoroutineStart.UNDISPATCHED) { queue.process(row.id) }
            withTimeoutOrNull(400) { secondRead.await() }
            release.complete(Unit)
            enqueue.await()
            assertEquals("tut_batch", process.await().sessionId)
            assertEquals("tut_batch", queue.list().single().sessionId)
            assertEquals(1, reads.get())
            assertEquals(1, uploads.get())
        }
    }

    @Test fun `opening an old practice citation uses its pinned layout after a newer course arrives`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val original = deps.packages.load("demo_attention").getOrThrow()
        val geometry = requireNotNull(deps.content.sourceLayoutFor(original.packageId))
        val pkg = original.copy(packageId = "retained", contentVersion = "1", documentId = "source-old")
        val bytes = "%PDF-example".toByteArray()
        val layout = geometry.copy(packageId = pkg.packageId, documentId = pkg.documentId, pdfBytes = bytes.size.toLong(), pdfSha256 = PdfBytes.sha256(bytes))
        deps.content.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.PRESET, sourceLayout = layout)).inputStream())
        deps.content.install(PackCodec.toBytes(PackCodec.build(pkg.copy(contentVersion = "2", documentVersion = "new-doc"), emptyList(), PackOrigin.PRESET)).inputStream())
        val presets = PresetSources(deps.content, SourceDocumentStore(tmp.newFolder())) { bytes.inputStream() }
        val catalog = MaterialCatalog(deps.content, deps.tutors, presets)
        val mat = catalog.materials().single { it.member == SpaceMember.LocalPackRef("retained", "1") }
        val citation = mat.nodes.first().evidence.single()
        val target = catalog.source(citation) as SourceTarget.Pdf
        assertArrayEquals(bytes, requireNotNull(target.document).file.readBytes())
    }

    @Test fun `ambiguous PDF upload cannot repeat until deliberate retry after restart`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val accepted = mutableListOf<DocumentResponse>()
        val seed = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session
        server({ path, _, _ -> when (path) {
            "/v1/documents" -> {
                val doc = DocumentResponse("doc-${accepted.size + 1}", "version", 1, "one")
                accepted += doc
                if (accepted.size == 1) -1 to "" else 201 to json.encodeToString(doc)
            }
            "/v1/documents/doc-2/tutor" -> 201 to json.encodeToString(seed.copy(sessionId = "tut_retry", mode = "mock",
                documentId = "doc-2", documentVersion = "version"))
            else -> errorResponse(404, "NOT_FOUND")
        } }) { transport ->
            val dir = tmp.newFolder()
            val space = deps.spaces.create("Selected")
            val client = TutorHttpClient(app, transport)
            val real = AndroidTutorRepository(app, deps.packages, deps.tutorScripts, {}, File(dir,"tutors"), http = client)
            fun reopen() = PdfImportQueue(File(dir,"queue"), { "%PDF-approved".toByteArray() }, real, deps.spaces)
            val row = reopen().enqueue(space.spaceId, listOf(ImportRequest.PdfFile("one", Uri.parse("content://selected/one"), null))).single()
            reopen().process(row.id)
            assertEquals("UPLOAD_OUTCOME_UNKNOWN", reopen().process(row.id).error)
            assertEquals(listOf("doc-1"), accepted.map { it.documentId })
            assertEquals("tut_retry", reopen().retryUnknownUpload(row.id).sessionId)
            assertEquals(listOf("doc-1", "doc-2"), accepted.map { it.documentId })
        }
    }
}
