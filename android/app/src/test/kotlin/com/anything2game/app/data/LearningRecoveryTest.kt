package com.anything2game.app.data

import android.net.Uri
import com.anything2game.app.AppDependencies
import com.anything2game.app.data.content.*
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.api.*
import com.anything2game.app.data.learning.*
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.source.*
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
        runBlocking {
            val newer = before.packages.load(case.packageId).getOrThrow().copy(contentVersion = "999")
            before.content.install(PackCodec.toBytes(PackCodec.build(newer, emptyList(), PackOrigin.IMPORT)).inputStream())
            assertEquals("999", before.packages.load(case.packageId).getOrThrow().contentVersion)
        }
        val dir = File(LocalProfile.SAMPLE.dir(app), "sessions")
        val legacy = case.copy(attemptId = "")
        File(dir, "demo_resnet.json").writeText(SessionCodec.encode(legacy))
        require(dir.resolve("versioned").deleteRecursively())
        repeat(2) {
            val deps = AndroidAppContainer(app)
            val activity = runBlocking {
                val space = deps.spaces.list().single { s -> s.members.any { it.version().contentId == case.packageId } }
                val node = deps.spaces.route(space.spaceId, "zh")!!.nodes.first { it.activity is ActivityRef.Practice &&
                    it.activity.activityId == case.levelId && it.activity.contentVersion == case.contentVersion }
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

    @Test fun `parallel player initialization cannot roll an answered checkpoint back to its prompt`() {
        val deps = AndroidAppContainer(app)
        val pkg = runBlocking { deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow() }
        val level = pkg.levels.first().levelId
        val active = PlayViewModel(deps, pkg.packageId, level, FrameId.CLASSIC)
        val initial = ready(active)
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : AppDependencies by deps {
            override val frames = object : com.anything2game.engine.ports.FrameRepository by deps.frames {
                override suspend fun load(packageId: String, version: String, frameId: FrameId): FrameOverlay? {
                    blocked.complete(Unit)
                    release.await()
                    return deps.frames.load(packageId, version, frameId)
                }
            }
        }
        val opening = PlayViewModel(slow, pkg.packageId, level, FrameId.CLASSIC, pkg.contentVersion)
        val end = System.nanoTime() + 10_000_000_000L
        while (!blocked.isCompleted && System.nanoTime() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
        assertTrue(blocked.isCompleted)
        val action = Action.Submit(ScriptedPlayer.correctAnswer(initial.item!!))
        active.dispatch(action)
        val answered = ready(active) { it.session.phase is Phase.Feedback }.session
        release.complete(Unit)
        assertEquals(answered, ready(opening).session)
        opening.dispatch(action)
        ShadowLooper.idleMainLooper()
        assertEquals(answered, runBlocking { deps.sessions.load(pkg.packageId, pkg.contentVersion) })
        assertEquals(1, runBlocking { deps.mastery.all(pkg.packageId, pkg.contentVersion).getValue(initial.unit!!.unitId).attempts })
        active.stopListeningToContext()
        opening.stopListeningToContext()
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
        deps.learningProgress.start(com.anything2game.contract.game.ReviewedGames.partition("en"), 27L)
        val ledger = File(app.filesDir, "learning.json")
        val backup = File(app.filesDir, "healthy-learning.bak")
        require(ledger.renameTo(backup) && ledger.mkdir())
        assertTrue(runCatching { repo.save(cache.copy(session = reply)) }.isFailure)
        val damaged = File(app.filesDir, "tutor-sessions/unreadable.json").apply { writeText("broken historical cache") }
        assertTrue(runCatching { AndroidAppContainer(app).learningProgress.projection() }.exceptionOrNull() is java.io.IOException)
        require(ledger.delete() && backup.renameTo(ledger))
        val after = AndroidAppContainer(app)
        assertEquals(actual.conceptId, after.learningProgress.projection().completed.single().activityId)
        assertEquals(reply.messages, after.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session.messages)
        assertTrue(after.learningProgress.projection().mastery.isEmpty())
        assertTrue(after.learningProgress.projection().played.any { it.contentId == "locker-search-en" })
        assertEquals("broken historical cache", damaged.readText())
    }

    @Test fun `sideload sharing document labels stays separate from owned tutor and its server practice`() = runBlocking {
        val initial = AndroidAppContainer(app)
        val pkg = initial.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
        val tutor = initial.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_owned", mode = "mock")
        val compiled = pkg.copy(packageId = "server-practice")
        val sideload = pkg.copy(packageId = "unrelated-pack", title = "Independent handout")
        val mislabeled = pkg.copy(packageId = "different-source", sources = pkg.sources.map { it.copy(text = it.text + " Different material follows.") })
        server({ path, _, _ -> when (path) {
            "/v1/compile-jobs" -> 202 to json.encodeToString(CompileJobStatus("owned-job", tutor.documentId,
                CompileJobState.READY, packageId = compiled.packageId))
            "/v1/packages/server-practice" -> 200 to ContractJson.encodePackage(compiled)
            else -> errorResponse(404, "NOT_FOUND")
        } }) { transport ->
            val live = AndroidAppContainer(app, transport.endpoint)
            live.tutors.save(TutorCache(tutor))
            assertEquals(compiled, live.tutors.preparePractice(tutor))
            live.content.install(PackCodec.toBytes(PackCodec.build(sideload, emptyList(), PackOrigin.IMPORT)).inputStream())
            live.content.install(PackCodec.toBytes(PackCodec.build(mislabeled, emptyList(), PackOrigin.SERVER)).inputStream())

            val reopened = AndroidAppContainer(app, transport.endpoint)
            val remote = SpaceMember.ServerDocumentRef(tutor.documentId, tutor.documentVersion)
            val serverSpace = reopened.spaces.list().single { remote in it.members }
            val nodes = reopened.spaces.route(serverSpace.spaceId, "en")!!.nodes
            assertTrue(nodes.any { it.activity.contentId == compiled.packageId })
            assertFalse(nodes.any { it.activity.contentId in setOf(sideload.packageId, mislabeled.packageId) })
            reopened.profiles.choose(LocalProfile.SAMPLE)
            reopened.tutors.save(TutorCache(tutor))
            assertTrue(reopened.materials.materials().any { it.member == SpaceMember.LocalPackRef(compiled.packageId, compiled.contentVersion) })
            reopened.profiles.choose(LocalProfile.GUEST)
            val script = reopened.tutorScripts.scriptFor(EXAMPLE_TUTOR_PACKAGE)!!.copy(packageId = compiled.packageId)
            reopened.content.install(PackCodec.toBytes(PackCodec.build(compiled, emptyList(), PackOrigin.SERVER, tutor = script)).inputStream())
            assertTrue(reopened.materials.materials().any { it.member == SpaceMember.LocalPackRef(compiled.packageId, compiled.contentVersion) })
        }
    }

    @Test fun `receipt write failure cannot publish an archive for migration on restart`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val store = tmp.newFolder()
        val packs = File(store, "packs")
        val receipt = File(store, "receipts.json")
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().copy(packageId = "authenticated")
        fun library() = ContentLibrary(listOf(DirectoryPackSource(packs)))
        fun receipts(content: ContentLibrary) = AuthenticatedCourses(receipt, content) { "owner" }
        fun spaces(content: ContentLibrary): SpaceRepository {
            val materials = MaterialCatalog(content, deps.tutors, authenticated = receipts(content)::owns)
            return SpaceRepository(File(store, "spaces"), materials::materials)
        }
        require(receipt.mkdir())
        assertTrue(runCatching { receipts(library()).install(pkg) }.isFailure)
        require(receipt.delete())
        assertTrue("no local Space may appear from a failed publication", spaces(library()).list().isEmpty())
        receipts(library()).install(pkg)
        val stored = spaces(library())
        val only = stored.list().single()
        assertEquals(listOf(SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)), only.members)
        assertTrue(stored.route(only.spaceId, "en")!!.nodes.any { it.activity.contentId == pkg.packageId })
    }

    @Test fun `Space migration sees no archive until its validated owner receipt is durable`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val store = tmp.newFolder()
        val packs = File(store, "packs")
        val receiptFile = File(store, "receipts")
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().copy(packageId = "concurrent-install")
        fun library() = ContentLibrary(listOf(DirectoryPackSource(packs)))
        fun spaces(): SpaceRepository {
            val content = library()
            val receipts = AuthenticatedCourses(receiptFile, content) { "owner" }
            return SpaceRepository(File(store, "spaces"), MaterialCatalog(content, deps.tutors, authenticated = receipts::owns)::materials)
        }
        val content = library()
        val receipts = AuthenticatedCourses(receiptFile, content) { "owner" }
        val pack = PackCodec.build(pkg, emptyList(), PackOrigin.SERVER)
        val record = receipts.publicationReceipt()
        val staged = CompletableDeferred<Unit>()
        val allowReceipt = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val allowPublish = CompletableDeferred<Unit>()
        val install = async(Dispatchers.IO) {
            content.install(PackCodec.toBytes(pack).inputStream(), beforeCoursePublish = { validated ->
                staged.complete(Unit)
                allowReceipt.await()
                record(validated)
                written.complete(Unit)
                allowPublish.await()
            })
        }
        try {
            withTimeout(10000) { staged.await() }
            assertFalse(receipts.owns(pack))
            assertTrue(spaces().list().isEmpty())
            allowReceipt.complete(Unit)
            withTimeout(10000) { written.await() }
            assertTrue(AuthenticatedCourses(receiptFile, library()) { "owner" }.owns(pack))
            assertTrue(spaces().list().isEmpty())
        } finally {
            allowReceipt.complete(Unit)
            allowPublish.complete(Unit)
        }
        install.await()
        val after = spaces()
        val only = after.list().single()
        assertEquals(listOf(SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)), only.members)
        assertTrue(after.route(only.spaceId, "en")!!.nodes.any { it.activity.contentId == pkg.packageId })
    }

    @Test fun `verification leaves one removable membership in the original and manually assigned Spaces`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val store = tmp.newFolder()
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().copy(packageId = "local-before-verification")
        val content = ContentLibrary(listOf(DirectoryPackSource(File(store, "packs"))))
        val receipts = AuthenticatedCourses(File(store, "receipts"), content) { "owner" }
        val materials = MaterialCatalog(content, deps.tutors, authenticated = receipts::owns)
        fun reopen() = SpaceRepository(File(store, "spaces"), materials::materials)
        content.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.SERVER)).inputStream())
        val original = reopen().list().single()
        val alias = SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion)
        assertEquals(listOf(alias), original.members)
        val other = reopen().create("Personal collection")
        reopen().add(other.spaceId, alias)
        val document = SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)
        // A snapshot written by the earlier migration can already contain both references.
        reopen().add(original.spaceId, document)
        receipts.install(pkg)
        val upgraded = reopen()
        assertEquals(setOf(original.spaceId, other.spaceId), upgraded.list().map { it.spaceId }.toSet())
        for (space in upgraded.list()) {
            assertEquals(listOf(document), space.members)
            assertEquals(listOf(document), upgraded.add(space.spaceId, alias).members)
            assertTrue(upgraded.route(space.spaceId, "en")!!.nodes.any { it.activity.contentId == pkg.packageId })
        }
        assertTrue(upgraded.remove(original.spaceId, document).members.isEmpty())
        assertTrue(upgraded.remove(other.spaceId, alias).members.isEmpty())
        for (space in reopen().list()) {
            assertTrue(space.members.isEmpty())
            assertNull(reopen().route(space.spaceId, "en"))
        }
        assertEquals(pkg, content.load(pkg.packageId, pkg.contentVersion).getOrThrow())
    }

    @Test fun `verification respects earlier removal and independent local packs with the same document labels`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val store = tmp.newFolder()
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().copy(packageId = "removed")
        val unrelated = pkg.copy(packageId = "independent")
        val content = ContentLibrary(listOf(DirectoryPackSource(File(store, "packs"))))
        val receipts = AuthenticatedCourses(File(store, "receipts"), content) { "owner" }
        val catalog = MaterialCatalog(content, deps.tutors, authenticated = receipts::owns)
        fun reopen() = SpaceRepository(File(store, "spaces"), catalog::materials)
        for (course in listOf(pkg, unrelated))
            content.install(PackCodec.toBytes(PackCodec.build(course, emptyList(), PackOrigin.SERVER)).inputStream())
        val alias = SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion)
        val independent = SpaceMember.LocalPackRef(unrelated.packageId, unrelated.contentVersion)
        val before = reopen().list()
        val removed = before.single { alias in it.members }
        val kept = before.single { independent in it.members }
        reopen().remove(removed.spaceId, alias)
        val pending = reopen().create("Removing an old duplicate")
        val document = SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)
        reopen().add(pending.spaceId, alias)
        reopen().add(pending.spaceId, document)
        reopen().remove(pending.spaceId, document)
        receipts.install(pkg)
        assertEquals(setOf(removed.spaceId, kept.spaceId, pending.spaceId), reopen().list().map { it.spaceId }.toSet())
        assertNull(reopen().route(removed.spaceId, "en"))
        assertNull(reopen().route(pending.spaceId, "en"))
        assertEquals(listOf(independent), reopen().list().single { it.spaceId == kept.spaceId }.members)
        val route = reopen().route(kept.spaceId, "en")!!
        assertEquals(setOf(unrelated.packageId), route.nodes.map { it.activity.contentId }.toSet())
        assertEquals(pkg, content.load(pkg.packageId, pkg.contentVersion).getOrThrow())
    }

    @Test fun `verification preserves an unacknowledged membership request and converges on one server relationship`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val store = tmp.newFolder()
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().copy(packageId = "in-flight")
        val content = ContentLibrary(listOf(DirectoryPackSource(File(store, "packs"))))
        val receipts = AuthenticatedCourses(File(store, "receipts"), content) { "owner" }
        val catalog = MaterialCatalog(content, deps.tutors, authenticated = receipts::owns)
        content.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.SERVER)).inputStream())
        val alias = SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion)
        val document = SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)
        var remote = Space("remote", pkg.title, 1)
        val received = mutableListOf<String>()
        server({ path, method, body -> when {
            path == "/v1/spaces" || method == "GET" -> 200 to json.encodeToString(remote)
            method == "PATCH" -> {
                val req = json.decodeFromString<RenameSpaceRequest>(body)
                if (req.expectedRevision != remote.revision) errorResponse(409, "SPACE_STALE") else {
                    remote = remote.copy(title = req.title, revision = remote.revision + 1)
                    200 to json.encodeToString(remote)
                }
            }
            else -> {
                val req = json.decodeFromString<SpaceMemberRequest>(body)
                val replay = method == "PUT" && body in received
                received += body
                when {
                    replay -> 200 to json.encodeToString(remote)
                    req.expectedRevision != remote.revision -> errorResponse(409, "SPACE_STALE")
                    else -> {
                        val members = if (method == "PUT") (remote.members + req.member).distinct() else remote.members - req.member
                        remote = remote.copy(members = members, revision = remote.revision + 1)
                        if (received.size == 1) errorResponse(503, "UNAVAILABLE") else 200 to json.encodeToString(remote)
                    }
                }
            }
        } }) { transport ->
            fun reopen() = SpaceRepository(File(store, "spaces"), catalog::materials, ActivityApi(transport))
            val original = reopen().list().single()
            reopen().rename(original.spaceId, "My collection")
            assertEquals("UNAVAILABLE", (runCatching { reopen().sync(original.spaceId) }.exceptionOrNull() as ApiException).code)
            assertEquals(listOf(alias), remote.members)
            receipts.install(pkg)
            val synced = reopen().sync(original.spaceId)
            assertEquals(listOf(document), synced.members)
            assertEquals("My collection", synced.title)
            assertEquals(received[0], received[1])
            assertEquals(listOf(document), reopen().list().single().members)
            reopen().remove(original.spaceId, document)
            assertTrue(reopen().sync(original.spaceId).members.isEmpty())
            assertNull(reopen().route(original.spaceId, "en"))
            assertEquals(pkg, content.load(pkg.packageId, pkg.contentVersion).getOrThrow())
        }
    }

    @Test fun `withdrawing a rejected add after verification also withdraws its queued identity change`() =
        assertVerifiedAddWithdrawal { spaces, id, _ ->
            spaces.withdrawRejected(id, spaces.rejectedEdits(id).keys.single())
        }

    @Test fun `removing a rejected add after verification also withdraws its queued identity change`() =
        assertVerifiedAddWithdrawal { spaces, id, document -> spaces.remove(id, document) }

    private fun assertVerifiedAddWithdrawal(withdraw: suspend (SpaceRepository, String, SpaceMember) -> Space) = runBlocking {
        val deps = AndroidAppContainer(app)
        val store = tmp.newFolder()
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().copy(packageId = "rejected-local")
        val content = ContentLibrary(listOf(DirectoryPackSource(File(store, "packs"))))
        val receipts = AuthenticatedCourses(File(store, "receipts"), content) { "owner" }
        val catalog = MaterialCatalog(content, deps.tutors, authenticated = receipts::owns)
        content.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.SERVER)).inputStream())
        val pack = SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion)
        val document = SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)
        val mutations = mutableListOf<Pair<String, SpaceMember>>()
        val remote = Space("remote", pkg.title, 1)
        server({ _, method, body ->
            if (method == "PUT" || method == "DELETE")
                mutations += method to json.decodeFromString<SpaceMemberRequest>(body).member
            if (method == "PUT" && mutations.size == 1) errorResponse(409, "SPACE_STALE") else 200 to json.encodeToString(remote)
        }) { transport ->
            fun reopen() = SpaceRepository(File(store, "spaces"), catalog::materials, ActivityApi(transport))
            val id = reopen().list().single().spaceId
            assertEquals("SPACE_STALE", (runCatching { reopen().sync(id) }.exceptionOrNull() as ApiException).code)
            receipts.install(pkg)
            assertEquals(listOf(document), reopen().list().single().members)
            assertTrue(withdraw(reopen(), id, document).members.isEmpty())
            val restarted = reopen()
            assertNull(restarted.route(id, "en"))
            assertTrue(restarted.sync(id).members.isEmpty())
            assertEquals(listOf("PUT" to pack), mutations)
            assertEquals(pkg, content.load(pkg.packageId, pkg.contentVersion).getOrThrow())
        }
    }

    private suspend fun server(handler: (String, String, String) -> Pair<Int, String>,
        install: () -> Pair<Int, String> = { 201 to json.encodeToString(InstallResponse("test", "sample")) },
        use: suspend (ApiTransport) -> Unit) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val path = ex.requestURI.path
            val reply = if (path == "/v1/installs") install()
                else if (ex.requestHeaders.getFirst("Authorization") != "Bearer sample") errorResponse(401, "UNAUTHORIZED")
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

    @Test fun `confirmed PDF languages survive a stopped selection and reach tutor creation after restart`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val session = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session
        val languages = mutableListOf<String>()
        var posts = 0
        server({ path, _, body -> when {
            path == "/v1/documents" -> {
                posts++
                201 to json.encodeToString(DocumentResponse("doc-$posts", "v1", 1, "Paper"))
            }
            path.endsWith("/tutor") -> {
                val lang = json.decodeFromString<CreateTutorRequest>(body).language
                languages += lang
                201 to json.encodeToString(session.copy(sessionId = "tut_$posts", mode = "mock", documentId = "doc-$posts",
                    documentVersion = "v1", language = lang))
            }
            else -> errorResponse(404, "NOT_FOUND")
        } }) { transport ->
            val root = tmp.newFolder()
            val space = deps.spaces.create("Seminar")
            fun queue(): PdfImportQueue {
                val tutor = AndroidTutorRepository(app, deps.packages, deps.tutorScripts, {}, File(root, "tutors"), http = TutorHttpClient(app, transport))
                return PdfImportQueue(File(root, "queue"), { "%PDF-sample".toByteArray() }, tutor, deps.spaces)
            }
            val selected = object : AppDependencies by deps { override val pdfImports = queue() }
            for (lang in listOf("en", "zh")) {
                val vm = com.anything2game.app.ui.space.SpaceViewModel(selected, lang, space.spaceId)
                val request = ImportRequest.PdfFile("Paper", Uri.parse("content://qa/$lang"), null)
                var journaled = false
                vm.importFiles(space.spaceId, listOf(request), "batch-$lang") { journaled = true; throw CancellationException("stopped") }
                val end = System.nanoTime() + 10_000_000_000L
                while (!journaled && System.nanoTime() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
                assertTrue(journaled)
                val fresh = queue()
                val row = fresh.list().single { it.batchId == "batch-$lang" }
                assertNotNull(fresh.process(row.id).sessionId)
            }
            assertEquals(listOf("en", "zh"), languages)
        }
    }

    @Test fun `each retained PDF edition opens the bytes measured for its own page geometry`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SourceDocumentStore(dir)
        for (n in 1..2) store.save("document", "edition-$n", 1, DocumentLayout(listOf(PageBox(0, n * 100f, 800f))), "%PDF-edition-$n".toByteArray())
        for (n in 1..2) {
            val page = requireNotNull(SourceDocumentStore(dir).load("document", "edition-$n"))
            assertEquals(n * 100f, page.layout.pages.single().width, 0f)
            assertArrayEquals("%PDF-edition-$n".toByteArray(), page.file.readBytes())
        }
    }

    @Test fun `interruption after PDF bytes arrive still opens the previous layout with its original bytes`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SourceDocumentStore(dir)
        val original = DocumentLayout(listOf(PageBox(0, 200f, 800f)))
        store.save("document", "edition-1", 1, original, "%PDF-original".toByteArray())
        val published = dir.walkTopDown().filter { it.extension == "json" }.associateWith { it.readBytes() }
        assertTrue(published.isNotEmpty())
        store.save("document", "edition-2", 1, DocumentLayout(listOf(PageBox(0, 400f, 600f))), "%PDF-later".toByteArray())
        // Reconstruct the durable state immediately before publication of edition 2's layout.
        dir.walkTopDown().filter { it.extension == "json" && it !in published }.forEach { check(it.delete()) }
        published.forEach { (file, bytes) -> file.writeBytes(bytes) }
        val reopened = SourceDocumentStore(dir)
        assertNull(reopened.load("document", "edition-2"))
        val page = requireNotNull(reopened.load("document", "edition-1"))
        assertEquals(original, page.layout)
        assertArrayEquals("%PDF-original".toByteArray(), page.file.readBytes())
    }

    @Test fun `one Spaces refresh reads the tutor inventory and learning ledger once across all cards`() {
        val base = AndroidAppContainer(app)
        val root = tmp.newFolder()
        val directory = DirectoryPackSource(File(root, "content"))
        val library = ContentLibrary(listOf(directory))
        var inventoryReads = 0
        var ledgerReads = 0
        val tutors = object : TutorRepository by base.tutors {
            override suspend fun list(): List<TutorSession> { inventoryReads++; return base.tutors.list() }
        }
        val catalog = MaterialCatalog(library, tutors)
        val spaces = SpaceRepository(File(root, "spaces"), catalog::materials)
        val progress = LearningProgress(File(root, "ledger"), "guest", beforeProjection = { ledgerReads++ })
        val dependencies = object : AppDependencies by base {
            override val materials = catalog
            override val spaces = spaces
            override val learningProgress = progress
        }
        runBlocking {
            val pkg = base.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            library.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.IMPORT)).inputStream())
            val material = catalog.materials().single()
            repeat(20) { spaces.add(spaces.create("Group $it").spaceId, material.member) }
        }
        inventoryReads = 0
        val vm = com.anything2game.app.ui.space.SpaceViewModel(dependencies, "en", null)
        vm.perform()
        val end = System.nanoTime() + 15_000_000_000L
        while (vm.busy && System.nanoTime() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
        assertNull(vm.error)
        assertEquals(20, vm.cards.size)
        assertTrue(vm.cards.all { it.next != null })
        assertEquals(1, inventoryReads)
        assertEquals(1, ledgerReads)
    }

    @Test fun `colliding scripted and uploaded lessons open and credit only their own session in every Space`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
        val script = deps.content.scriptFor(pkg.packageId)!!
        val other = pkg.copy(packageId = "another-script", title = "Independent lesson")
        val otherScript = script.copy(packageId = other.packageId, blueprint = script.blueprint.copy(concepts = script.blueprint.concepts.map {
            it.copy(explanation = "An independent author's explanation.", beats = emptyList())
        }))
        deps.content.install(PackCodec.toBytes(PackCodec.build(other, emptyList(), PackOrigin.IMPORT, otherScript)).inputStream())
        val owned = deps.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_owned", mode = "mock", title = "Server lesson")
        deps.tutors.save(TutorCache(owned))
        val a = SpaceMember.LocalPackRef(pkg.packageId, pkg.contentVersion)
        val b = SpaceMember.LocalPackRef(other.packageId, other.contentVersion)
        val uploaded = SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)
        val together = deps.spaces.create("All three")
        for (member in listOf(a, b, uploaded)) deps.spaces.add(together.spaceId, member)
        val also = deps.spaces.create("Read in another group")
        deps.spaces.add(also.spaceId, b)
        val path = deps.spaces.route(together.spaceId, "en")!!
        val concept = owned.questions.first().conceptId
        val nodes = path.nodes.filter { it.activity is ActivityRef.Tutor && it.activity.activityId == concept }
        val keys = nodes.associateBy { (deps.materials.resolve(it.activity, "en") as OpenActivity.Tutor).sessionKey }
        assertEquals(setOf(localTutorKey(pkg), localTutorKey(other), owned.sessionId), keys.keys)
        val key = localTutorKey(other)
        var cache = deps.tutors.open(key).cache
        cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER })
        cache = cache.copy(session = deps.tutors.assess(cache, "start"))
        val reply = deps.tutors.turn(cache, TutorIntent.START, "", "read")
        assertTrue(reply.messages.last().text.contains("independent author"))
        deps.tutors.save(cache.copy(session = reply))
        val statuses = deps.materials.nodeStatuses(path, deps.learningProgress, 0)
        for ((id, node) in keys) assertEquals(if (id == key) com.anything2game.engine.progress.NodeStatus.COMPLETE
            else com.anything2game.engine.progress.NodeStatus.AVAILABLE, statuses[node.nodeId])
        val repeat = deps.spaces.route(also.spaceId, "en")!!
        assertEquals(com.anything2game.engine.progress.NodeStatus.COMPLETE, deps.materials.nodeStatuses(repeat, deps.learningProgress, 0)[keys.getValue(key).nodeId])
        assertEquals(1, deps.learningProgress.projection().completed.size)
    }

    @Test fun `old tutor credit binds only to a saved message that proves which lesson was read`() = runBlocking {
        val deps = AndroidAppContainer(app)
        var cache = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache
        cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER })
        cache = cache.copy(session = deps.tutors.assess(cache, "assess"))
        val taught = deps.tutors.turn(cache, TutorIntent.START, "", "read")
        deps.tutors.save(cache.copy(session = taught))
        deps.tutors.save(TutorCache(taught.copy(sessionId = "tut_elsewhere", mode = "mock", messages = emptyList())))
        val ledger = File(app.filesDir, "learning.json")
        val old = com.anything2game.contract.progress.ActivityKey("guest", com.anything2game.contract.progress.ActivityKind.TUTOR,
            taught.documentId, taught.documentVersion, TutorRules.orderedQuestions(taught).first().conceptId, 1)
        fun receipt(id: String) = com.anything2game.contract.progress.CompletionRecord.at(id, old,
            com.anything2game.contract.progress.ActivityOutcome.READ, 0L, "UTC")
        val known = receipt(stableId("read", taught.sessionId, taught.messages.last().id, taught.documentId, taught.documentVersion))
        val unknown = receipt("unattributable-old-read")
        ledger.writeText("""{"events":[${json.encodeToString(known)},${json.encodeToString(unknown)}]}""")
        val reopened = AndroidAppContainer(app)
        val local = SpaceMember.LocalPackRef(EXAMPLE_TUTOR_PACKAGE, reopened.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().contentVersion)
        val remote = SpaceMember.ServerDocumentRef(taught.documentId, taught.documentVersion)
        val both = reopened.spaces.create("Both")
        for (member in listOf(local, remote)) reopened.spaces.add(both.spaceId, member)
        val route = requireNotNull(reopened.spaces.route(both.spaceId, "en"))
        val status = reopened.materials.nodeStatuses(route, reopened.learningProgress, 0)
        val lessons = route.nodes.filter { it.activity is ActivityRef.Tutor && it.activity.activityId == old.activityId }
        assertEquals(2, lessons.size)
        for (node in lessons) {
            val opened = reopened.materials.resolve(node.activity) as OpenActivity.Tutor
            assertEquals(if (opened.sessionKey == taught.sessionId) com.anything2game.engine.progress.NodeStatus.COMPLETE
                else com.anything2game.engine.progress.NodeStatus.AVAILABLE, status[node.nodeId])
        }
        assertEquals(setOf("1970-01-01"), reopened.learningProgress.projection().days)
    }

    @Test fun `an unqualified historical server node cannot borrow a matching unverified script`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val material = deps.materials.materials().first { it.localPack?.packageId == EXAMPLE_TUTOR_PACKAGE }
        val node = material.nodes.first { it.activity is ActivityRef.Tutor }
        val old = ActivityRef.Tutor(material.source.contentId, material.source.contentVersion, node.activity.activityId)
        val session = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session.copy(sessionId = "tut_actual", mode = "mock")
        val path = LearningPath("old", "1", "s", "Server route", session.language, listOf(node.copy(activity = old)))
        assertTrue(runCatching { deps.materials.verify(path) }.isFailure)
        deps.tutors.save(TutorCache(session))
        deps.materials.verify(path)
        assertEquals("tut_actual", (deps.materials.resolve(old, session.language) as OpenActivity.Tutor).sessionKey)
    }

    @Test fun `two measured editions with identical text labels retain their respective page files alongside an upload`() = runBlocking {
        val directory = tmp.newFolder()
        val layouts = object : SourceLayouts {
            override suspend fun sourceLayoutFor(packageId: String) = sourceLayoutFor(packageId, "2")
            override suspend fun sourceLayoutFor(packageId: String, contentVersion: String): PresetSourceLayout {
                val pdf = "%PDF-printed-$contentVersion".toByteArray()
                return PresetSourceLayout(packageId = packageId, documentId = "paper", documentVersion = "same-text",
                    pdfSha256 = PdfBytes.sha256(pdf), pdfBytes = pdf.size.toLong(), pageCount = 1,
                    layout = DocumentLayout(listOf(PageBox(0, if (contentVersion == "1") 200f else 400f, 800f))))
            }
        }
        val uploadedLayout = DocumentLayout(listOf(PageBox(0, 600f, 1000f)))
        SourceDocumentStore(directory).save("paper", "same-text", 1, uploadedLayout, "%PDF-upload".toByteArray())
        fun reopen() = PresetSources(layouts, SourceDocumentStore(directory))
        for (version in listOf("1", "2"))
            assertTrue(reopen().attach("pack", version) { "%PDF-printed-$version".byteInputStream() } is AttachResult.Accepted)
        for (version in listOf("1", "2")) {
            val page = requireNotNull(reopen().load("pack", "paper", "same-text", version))
            assertArrayEquals("%PDF-printed-$version".toByteArray(), page.file.readBytes())
            assertEquals(if (version == "1") 200f else 400f, page.layout.pages.single().width, 0f)
        }
        val imported = requireNotNull(SourceDocumentStore(directory).load("paper", "same-text"))
        assertEquals(uploadedLayout, imported.layout)
        assertArrayEquals("%PDF-upload".toByteArray(), imported.file.readBytes())
    }

    @Test fun `an unreadable confirmed URI keeps an actionable file failure without dispatching an upload`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val queue = PdfImportQueue(tmp.newFolder(), { throw java.io.FileNotFoundException("expired content grant") }, deps.tutors, deps.spaces)
        val row = queue.enqueue("space", listOf(ImportRequest.PdfFile("Paper", Uri.parse("content://expired/paper"), null))).single()
        assertEquals("FILE_UNAVAILABLE", row.error)
        assertNull(row.document)
    }

    @Test fun `a saved fallback route stays pinned to its chosen tutor after another language is added`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val original = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session
        val zh = original.copy(sessionId = "tut_chinese", mode = "mock", language = "zh")
        val en = original.copy(sessionId = "tut_english", mode = "mock", language = "en")
        deps.tutors.save(TutorCache(en))
        val ref = ActivityRef.Tutor(original.documentId, original.documentVersion, original.questions.first().conceptId, sessionId = zh.sessionId)
        val node = PathNode("first-lesson", "Quoted lesson", ref)
        val path = LearningPath("path", "1", "space", "Seminar", "en", listOf(node))
        assertTrue(runCatching { deps.materials.verify(path) }.isFailure)
        deps.tutors.save(TutorCache(zh))
        deps.materials.verify(path)
        assertEquals(zh.sessionId, (deps.materials.resolve(node.activity, "en") as OpenActivity.Tutor).sessionKey)
    }

    @Test fun `an unidentified old read cannot unlock a prerequisite on a different tutor`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val m = deps.materials.materials().single { it.localPack?.packageId == EXAMPLE_TUTOR_PACKAGE }
        val source = m.nodes.first { it.activity is ActivityRef.Tutor }
        val old = ActivityRef.Tutor(m.source.contentId, m.source.contentVersion, source.activity.activityId)
        val next = m.nodes.first { it.activity is ActivityRef.Practice }.copy(prerequisites = listOf(source.nodeId))
        val path = LearningPath("old", "1", "space", "Retained", "en", listOf(source.copy(activity = old), next))
        val event = com.anything2game.contract.progress.CompletionRecord.at("unknown", old.key("guest"),
            com.anything2game.contract.progress.ActivityOutcome.READ, 0L, "UTC")
        val file = tmp.newFile().apply { writeText("""{"events":[${json.encodeToString(event)}]}""") }
        val progress = LearningProgress(file, "guest")
        val statuses = deps.materials.nodeStatuses(path, progress, 0L)
        assertEquals(com.anything2game.engine.progress.NodeStatus.NEEDS_UPDATE, statuses[source.nodeId])
        assertEquals(com.anything2game.engine.progress.NodeStatus.LOCKED, statuses[next.nodeId])
    }

    @Test fun `a legacy PDF layout remains readable beside a newly saved edition`() = runBlocking {
        val directory = tmp.newFolder()
        val base = PdfBytes.sha256("paper".toByteArray())
        val layout = DocumentLayout(listOf(PageBox(0, 400f, 800f)))
        File(directory, "$base.pdf").writeText("%PDF-legacy")
        File(directory, "$base.layout.json").writeText("""{"documentId":"paper","documentVersion":"v1","pageCount":1,"layout":${json.encodeToString(layout)}}""")
        val store = SourceDocumentStore(directory)
        store.save("paper", "v2", 1, DocumentLayout(listOf(PageBox(0, 360f, 600f))), "%PDF-new".toByteArray())
        val old = requireNotNull(SourceDocumentStore(directory).load("paper", "v1"))
        assertEquals(layout, old.layout)
        assertEquals("%PDF-legacy", old.file.readText())
    }

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
        assertNull(presets.available(pkg.packageId))
        assertEquals(pkg.documentId, requireNotNull(presets.available(pkg.packageId, "1")).documentId)
        assertTrue(presets.attach(pkg.packageId, "1") { bytes.inputStream() } is AttachResult.Accepted)
        val catalog = MaterialCatalog(deps.content, deps.tutors, presets)
        val mat = catalog.materials().single { it.member == SpaceMember.LocalPackRef("retained", "1") }
        val citation = mat.nodes.first().evidence.single()
        assertArrayEquals(bytes, requireNotNull(originalOf(catalog, deps.tutors, presets, citation)).file.readBytes())
    }

    @Test fun `a retained tutor card reads progress at the requested version`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val original = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
        val script = requireNotNull(deps.tutorScripts.scriptFor(original.packageId))
        for (version in listOf("1", "2")) {
            val pkg = original.copy(packageId = "retained-tutor", contentVersion = version)
            deps.content.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.PRESET,
                tutor = script.copy(packageId = pkg.packageId, contentVersion = version))).inputStream())
            if (version == "1") deps.tutors.save(deps.tutors.open(localTutorKey(pkg)).cache.copy(focusChosen = true))
        }
        assertNull(deps.tutors.progressFor("retained-tutor"))
        assertNotNull(deps.tutors.progressFor("retained-tutor", "1"))
        assertNull(deps.tutors.progressFor("retained-tutor", "2"))
    }

    /** What the phone would open for this citation: the catalog says which file, the source library finds its original. */
    private suspend fun originalOf(catalog: MaterialCatalog, tutors: TutorRepository, presets: PresetSources, citation: com.anything2game.contract.game.EvidenceRef): SourceDocument? {
        val target = catalog.source(citation) as SourceTarget.Pdf
        return (phoneSourceLibrary(tutors, presets).open(target.key, SourceText("t", target.chunks)).original as? Original.Pdf)?.document
    }

    @Test fun `matching bundled evidence leaves uploaded profile PDF and layout intact`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val pkg = deps.packages.load("demo_attention").getOrThrow()
        val bundled = "%PDF-bundled".toByteArray()
        val layout = requireNotNull(deps.content.sourceLayoutFor(pkg.packageId)).copy(pdfBytes = bundled.size.toLong(), pdfSha256 = PdfBytes.sha256(bundled))
        val content = ContentLibrary(listOf(DirectoryPackSource(tmp.newFolder())))
        content.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.PRESET, sourceLayout = layout)).inputStream())
        val stored = SourceDocumentStore(File(app.filesDir, "source-documents"))
        val uploaded = "%PDF-profile-upload".toByteArray()
        val original = DocumentLayout(pages = listOf(PageBox(0, 321f, 654f)))
        stored.save(pkg.documentId, pkg.documentVersion, 1, original, uploaded)
        var bundledOpens = 0
        val sources = PresetSources(content, stored) { bundledOpens++; bundled.inputStream() }
        val catalog = MaterialCatalog(content, deps.tutors, sources)
        val evidence = catalog.materials().single().nodes.first().evidence.first()
        val selected = requireNotNull(originalOf(catalog, deps.tutors, sources, evidence))
        assertArrayEquals(uploaded, selected.file.readBytes())
        assertEquals(0, bundledOpens)
        val reopened = requireNotNull(stored.load(pkg.documentId, pkg.documentVersion))
        assertEquals(original, reopened.layout)
        assertNull(reopened.pdfSha256)
        assertArrayEquals(uploaded, reopened.file.readBytes())
    }

    @Test fun `upload arriving while a bundled source opens keeps the imported bytes and geometry`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val pkg = deps.packages.load("demo_attention").getOrThrow()
        val bundled = "%PDF-bundled".toByteArray()
        val layout = requireNotNull(deps.content.sourceLayoutFor(pkg.packageId)).copy(pdfBytes = bundled.size.toLong(), pdfSha256 = PdfBytes.sha256(bundled))
        val content = ContentLibrary(listOf(DirectoryPackSource(tmp.newFolder())))
        content.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.PRESET, sourceLayout = layout)).inputStream())
        val store = SourceDocumentStore(File(app.filesDir, "source-documents"))
        val opening = CompletableDeferred<Unit>()
        val proceed = java.util.concurrent.CountDownLatch(1)
        val presets = PresetSources(content, store) {
            opening.complete(Unit)
            check(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
            bundled.inputStream()
        }
        val catalog = MaterialCatalog(content, deps.tutors, presets)
        val evidence = catalog.materials().single().nodes.first().evidence.first()
        val lookup = async(Dispatchers.IO) { originalOf(catalog, deps.tutors, presets, evidence) }
        val uploaded = "%PDF-profile-upload".toByteArray()
        val original = DocumentLayout(pages = listOf(PageBox(0, 321f, 654f)))
        try {
            withTimeout(10000) { opening.await() }
            store.save(pkg.documentId, pkg.documentVersion, 1, original, uploaded)
        } finally { proceed.countDown() }
        val selected = requireNotNull(lookup.await())
        assertArrayEquals(uploaded, selected.file.readBytes())
        val reopened = requireNotNull(store.load(pkg.documentId, pkg.documentVersion))
        assertEquals(original, reopened.layout)
        assertArrayEquals(uploaded, reopened.file.readBytes())
    }

    @Test fun `matching editions with identical document versions can use geometry in the second pack`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val original = deps.packages.load("demo_attention").getOrThrow()
        val pkg = original.copy(packageId = "retained-edition", documentId = "retained-source", contentVersion = "1")
        val bytes = "%PDF-retained".toByteArray()
        val layout = requireNotNull(deps.content.sourceLayoutFor(original.packageId)).copy(packageId = pkg.packageId,
            documentId = pkg.documentId, pdfBytes = bytes.size.toLong(), pdfSha256 = PdfBytes.sha256(bytes))
        val first = DirectoryPackSource(tmp.newFolder())
        val second = DirectoryPackSource(tmp.newFolder())
        first.install(PackCodec.toBytes(PackCodec.build(pkg, emptyList(), PackOrigin.PRESET)).inputStream()).getOrThrow()
        second.install(PackCodec.toBytes(PackCodec.build(pkg.copy(contentVersion = "2"), emptyList(), PackOrigin.PRESET,
            sourceLayout = layout)).inputStream()).getOrThrow()
        val library = ContentLibrary(listOf(first, second))
        val sources = PresetSources(library, SourceDocumentStore(tmp.newFolder())) { bytes.inputStream() }
        val catalog = MaterialCatalog(library, deps.tutors, sources)
        val evidence = catalog.materials().first().nodes.first().evidence.single()
        assertArrayEquals(bytes, requireNotNull(originalOf(catalog, deps.tutors, sources, evidence)).file.readBytes())
    }

    @Test fun `configuration failure before PDF dispatch supports ordinary retry`() = runBlocking {
        val deps = AndroidAppContainer(app)
        val space = deps.spaces.create("Selected")
        val root = tmp.newFolder()
        val seed = deps.tutors.open(EXAMPLE_TUTOR_PACKAGE).cache.session
        fun queue(transport: ApiTransport): PdfImportQueue {
            val tutor = AndroidTutorRepository(app, deps.packages, deps.tutorScripts, {}, File(root, "tutors"), http = TutorHttpClient(app, transport))
            return PdfImportQueue(File(root, "queue"), { "%PDF-review".toByteArray() }, tutor, deps.spaces)
        }
        val offline = queue(ApiTransport(app, ""))
        val row = offline.enqueue(space.spaceId, listOf(ImportRequest.PdfFile("One", Uri.parse("content://qa/one"), null))).single()
        assertEquals("NOT_CONFIGURED", offline.process(row.id).error)
        var posts = 0
        var installs = 0
        server({ path, _, _ -> when (path) {
            "/v1/documents" -> { posts++; 201 to json.encodeToString(DocumentResponse("doc", "1", 1, "One")) }
            "/v1/documents/doc/tutor" -> 201 to json.encodeToString(seed.copy(sessionId = "tut_ok", mode = "mock", documentId = "doc", documentVersion = "1"))
            else -> errorResponse(404, "NOT_FOUND")
        } }, install = {
            installs++
            if (installs == 1) errorResponse(503, "INSTALL_TEMPORARY_FAILURE") else 201 to json.encodeToString(InstallResponse("test", "sample"))
        }) { transport ->
            assertEquals("INSTALL_TEMPORARY_FAILURE", queue(transport).process(row.id).error)
            assertEquals(0, posts)
            assertEquals("tut_ok", queue(transport).process(row.id).sessionId)
            assertEquals(1, posts)
        }
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
