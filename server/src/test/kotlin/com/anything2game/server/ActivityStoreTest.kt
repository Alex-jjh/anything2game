package com.anything2game.server

import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.space.*
import com.anything2game.engine.game.GameRuntime
import com.anything2game.server.activity.SpaceService
import com.anything2game.server.api.Quotas
import com.anything2game.server.config.Limits
import com.anything2game.server.api.ApiException
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.llm.*
import com.anything2game.server.store.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager
import org.flywaydb.core.Flyway

private fun spaces(store: Store, limits: Limits = Limits()) = SpaceService(store, Quotas(store, limits))

private class BlockingActivityModel(private val label: String) : LlmClient {
    private val fake = FakeLlmClient()
    override val modelId = fake.modelId
    val arrived = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    override suspend fun completeJson(request: LlmRequest): String {
        if (request.label == label && arrived.complete(Unit)) {
            withContext(NonCancellable) { resume.await() }
        }
        return fake.completeJson(request)
    }
}

abstract class ActivityStoreTest : StoreBackendTest() {
    private fun instance(worker: Store, llm: LlmClient, concurrency: Int = 1): AppContext = AppContext(ServerConfig(workerPollMillis = 15, workerConcurrency = concurrency),
        worker, LlmRouter(llm, llm, llm), workerId = "activity-worker")

    private suspend fun done(id: String) = withTimeout(10_000) {
        var job = store.getJob(id)!!
        while (!job.state.isTerminal) { delay(15); job = store.getJob(id)!! }
        job
    }

    @Test fun `parallel request retries submit one typed job beside a legacy job using the same key`() = runBlocking {
        val doc = newDocument()
        val compile = store.createJob(newJob(doc, clientRequestId = "key"))
        val owner = doc.ownerId
        val req = GenerationRequest(GenerationInput.ConceptGame("机会成本", "zh"), "key")
        val clients = listOf(store, anotherInstance()).map { spaces(it, Limits(compilesPerInstallPerDay = 1)) }
        val jobs = (1..12).map { i -> async(Dispatchers.IO) { clients[i % 2].submit(owner, req) } }.awaitAll()
        assertEquals(1, jobs.map { it.jobId }.distinct().size)
        assertThrows(ApiException::class.java) { Quotas(store, Limits(compilesPerInstallPerDay = 1)).consumeCompile(owner) }
        val job = jobs.first()
        assertNull(job.documentId)
        assertNull(job.options)
        val ctx = instance(anotherInstance(), FakeLlmClient())
        try {
            ctx.start()
            assertEquals(CompileJobState.READY, done(compile.jobId).state)
            val result = done(job.jobId)
            assertEquals(ContentKind.GAME, result.generationStatus().result!!.kind)
            assertTrue(result.generationStatus().fixture)
            val bytes = anotherInstance().readArtifact(job.jobId)!!
            val pack = PackCodec.readActivity(bytes.inputStream(), emptySet(), validateGame = GameRuntime::validate).getOrThrow()
            assertEquals("zh", pack.manifest.language)
        } finally { ctx.close() }
    }

    @Test fun `global cap rejection preserves the remaining owner allowance`() {
        val limits = Limits(compilesPerInstallPerDay = 1, globalCompilesPerDay = 1)
        val quotas = Quotas(store, limits, clock = { 0L })
        quotas.consumeCompile("full")
        assertThrows(ApiException::class.java) { quotas.consumeCompile("fresh") }
        Quotas(anotherInstance(), limits.copy(globalCompilesPerDay = 2), clock = { 0L }).consumeCompile("fresh")
    }

    @Test fun `concurrent paths for one revision activate only one immutable version`() = runBlocking {
        val model = BlockingActivityModel("space-path")
        val ctx = instance(anotherInstance(), model, concurrency = 2)
        val doc = newDocument()
        ctx.tutor.create(doc, com.anything2game.contract.tutor.CreateTutorRequest("en", "lesson")) {}
        val space = ctx.spaces.create(doc.ownerId, CreateSpaceRequest("Seminar", "s"))
        val member = SpaceMember.ServerDocumentRef(doc.documentId, doc.documentVersion)
        val revised = ctx.spaces.member(doc.ownerId, space.spaceId, SpaceMemberRequest(member, space.revision, "add"), true)
        val input = GenerationInput.SpacePath(space.spaceId, revised.revision)
        try {
            val first = ctx.spaces.submit(doc.ownerId, GenerationRequest(input, "first"))
            ctx.start()
            withTimeout(10_000) { model.arrived.await() }
            val second = ctx.spaces.submit(doc.ownerId, GenerationRequest(input, "second"))
            val winner = done(second.jobId)
            assertEquals(CompileJobState.READY, winner.state)
            model.resume.complete(Unit)
            assertEquals("SPACE_STALE", done(first.jobId).error?.code)
            assertNull(store.readArtifact(first.jobId))
            assertEquals(winner.generationStatus().result?.content, ctx.spaces.get(doc.ownerId, space.spaceId).activePathVersion)
        } finally { model.resume.complete(Unit); ctx.close() }
    }

    @Test fun `practice catalog prefers the requested language and otherwise uses the available one`() {
        val doc = newDocument()
        val ctx = instance(anotherInstance(), FakeLlmClient())
        val space = Space("space", "read", 1, listOf(SpaceMember.ServerDocumentRef(doc.documentId, doc.documentVersion)))
        val sources = com.anything2game.server.activity.PathSources(store, ctx.tutor)
        fun practice(lang: String) = newJob(doc, createdAt = if (lang == "en") 1L else 2L).also { job ->
            store.saveJob(job.copy(state = CompileJobState.READY))
            store.savePackage(StoredPackage(doc.ownerId, job.jobId, true, Examples.retrieval.copy(
                packageId = job.packageId, documentId = doc.documentId, documentVersion = doc.documentVersion, language = lang)))
        }
        try {
            val english = practice("en")
            assertEquals(english.packageId, sources.collect(doc.ownerId, space, "zh").nodes.single().activity.contentId)
            val chinese = practice("zh")
            assertEquals(english.packageId, sources.collect(doc.ownerId, space, "en").nodes.single().activity.contentId)
            assertEquals(chinese.packageId, sources.collect(doc.ownerId, space, "zh").nodes.single().activity.contentId)
        } finally { ctx.close() }
    }

    @Test fun `late typed game response after cancellation does not write an archive`() = runBlocking {
        val model = BlockingActivityModel("game-spec")
        val ctx = instance(anotherInstance(), model)
        val job = ctx.spaces.submit("owner", GenerationRequest(GenerationInput.ConceptGame("binary search"), "g"))
        try {
            ctx.start()
            withTimeout(10_000) { model.arrived.await() }
            ctx.runner.cancel(job.jobId)
            model.resume.complete(Unit)
            assertEquals(CompileJobState.CANCELLED, done(job.jobId).state)
            assertNull(store.readArtifact(job.jobId))
        } finally { model.resume.complete(Unit); ctx.close() }
    }

    @Test fun `a provider answer after another worker takes over cannot publish even if the source is valid`() = runBlocking {
        val model = BlockingActivityModel("game-spec")
        val ctx = instance(anotherInstance(), model)
        val job = ctx.spaces.submit("owner", GenerationRequest(GenerationInput.ConceptGame("binary search"), "first"))
        try {
            ctx.start()
            withTimeout(10_000) { model.arrived.await() }
            val takeover = anotherInstance().claimNext("new-owner", 60_000, System.currentTimeMillis() + 60_000)!!
            assertEquals(job.jobId, takeover.jobId)
            val next = ctx.spaces.submit("owner", GenerationRequest(GenerationInput.ConceptGame("opportunity cost"), "next"))
            model.resume.complete(Unit)
            // With one worker slot, finishing the second job proves the first provider call returned.
            assertEquals(CompileJobState.READY, done(next.jobId).state)
            assertEquals("new-owner", store.getJob(job.jobId)!!.claimedBy)
            assertNull(store.readArtifact(job.jobId))
        } finally { model.resume.complete(Unit); ctx.close() }
    }

    @Test fun `one instance never claims the same still-running model call twice after expiry`() = runBlocking {
        val model = BlockingActivityModel("game-spec")
        val ctx = instance(anotherInstance(), model, concurrency = 2)
        val job = ctx.spaces.submit("owner", GenerationRequest(GenerationInput.ConceptGame("binary search"), "first"))
        try {
            ctx.start()
            withTimeout(10_000) { model.arrived.await() }
            store.updateJob(job.jobId) { it.copy(leaseUntil = System.currentTimeMillis() - 1) }
            val next = ctx.spaces.submit("owner", GenerationRequest(GenerationInput.ConceptGame("opportunity cost"), "next"))
            ctx.runner.wake()
            assertEquals(CompileJobState.READY, done(next.jobId).state)
            assertEquals(1, store.getJob(job.jobId)!!.attempts)
            assertNull(store.readArtifact(job.jobId))
            model.resume.complete(Unit)
            assertEquals(CompileJobState.READY, done(job.jobId).state)
        } finally { model.resume.complete(Unit); ctx.close() }
    }

    @Test fun `an expired worker cannot reacquire its former lease by renewing it`() {
        val job = spaces(store).submit("owner", GenerationRequest(GenerationInput.ConceptGame("binary search"), "g"))
        store.claimNext("late", 20, 100)
        assertFalse(store.renewLease(job.jobId, "late", 20, 121))
        assertEquals("new", anotherInstance().claimNext("new", 20, 121)!!.claimedBy)
    }

    @Test fun `source deletion during path synthesis prevents publication and activation`() = runBlocking {
        val doc = newDocument()
        val model = BlockingActivityModel("space-path")
        val ctx = instance(anotherInstance(), model)
        val tutor = ctx.tutor.create(doc, com.anything2game.contract.tutor.CreateTutorRequest(language = "en", clientRequestId = "tutorial")) {}
        assertTrue(tutor.questions.isNotEmpty())
        val space = ctx.spaces.create(doc.ownerId, CreateSpaceRequest("Seminar", "create"))
        val changed = ctx.spaces.member(doc.ownerId, space.spaceId,
            SpaceMemberRequest(SpaceMember.ServerDocumentRef(doc.documentId, doc.documentVersion), space.revision, "add"), true)
        val job = ctx.spaces.submit(doc.ownerId, GenerationRequest(GenerationInput.SpacePath(space.spaceId, changed.revision), "g"))
        try {
            ctx.start()
            withTimeout(10_000) { model.arrived.await() }
            store.deleteDocument(doc.documentId)
            model.resume.complete(Unit)
            val result = done(job.jobId)
            assertEquals(CompileJobState.CANCELLED, result.state)
            assertEquals("SOURCE_REMOVED", result.error?.code)
            assertNull(store.readArtifact(job.jobId))
            assertNull(ctx.spaces.get(doc.ownerId, space.spaceId).activePathVersion)
        } finally { model.resume.complete(Unit); ctx.close() }
    }

    @Test fun `document deletion also cancels a queued path before it can silently omit the removed document`() {
        val doc = newDocument()
        val spaces = spaces(store)
        val space = spaces.create(doc.ownerId, CreateSpaceRequest("Seminar", "new"))
        val changed = spaces.member(doc.ownerId, space.spaceId, SpaceMemberRequest(
            SpaceMember.ServerDocumentRef(doc.documentId, doc.documentVersion), space.revision, "member"), true)
        val job = spaces.submit(doc.ownerId, GenerationRequest(GenerationInput.SpacePath(space.spaceId, changed.revision), "path"))
        anotherInstance().deleteDocument(doc.documentId)
        assertEquals(CompileJobState.CANCELLED, store.getJob(job.jobId)!!.state)
        assertNull(store.claimNext("worker", 5000, System.currentTimeMillis()))
    }

    @Test fun `a local-only Space reports the excluded materials without publishing`() = runBlocking {
        val ctx = instance(anotherInstance(), FakeLlmClient())
        val space = ctx.spaces.create("owner", CreateSpaceRequest("Seminar", "s"))
        val local = SpaceMember.LocalPackRef("私有笔记", "local:v1")
        val changed = ctx.spaces.member("owner", space.spaceId, SpaceMemberRequest(local, space.revision, "a"), true)
        val job = ctx.spaces.submit("owner", GenerationRequest(GenerationInput.SpacePath(space.spaceId, changed.revision), "g"))
        try {
            ctx.start()
            val result = done(job.jobId).generationStatus()
            assertEquals("NO_AVAILABLE_ACTIVITIES", result.error?.code)
            assertEquals(listOf(local), result.excludedMembers)
            assertNull(result.result)
        } finally { ctx.close() }
    }
}

class FileActivityStoreTest : ActivityStoreTest() {
    override fun newBackend() = FileBackend()

    @Test fun `reopening after a failed job-file write recovers the charged request once`() {
        val dir = Files.createTempDirectory("a2g-admit-restart")
        try {
            val fs = FileStore(dir)
            val request = GenerationRequest(GenerationInput.ConceptGame("binary search"), "one")
            val owner = "owner"
            val limits = Limits(compilesPerInstallPerDay = 1, globalCompilesPerDay = 2)
            val jobs = dir.resolve("jobs")
            val backup = dir.resolve("saved-jobs")
            // The actual file store commits its admission journal and counters, then fails to create
            // the job file because its parent is a regular file. Restore the directory after the interruption.
            val failing = object : Store by fs {
                override fun admit(charges: List<QuotaCharge>, job: JobRecord?) {
                    Files.move(jobs, backup)
                    Files.createFile(jobs)
                    fs.admit(charges, job)
                }
            }
            assertThrows(java.io.IOException::class.java) { spaces(failing, limits).submit(owner, request) }
            Files.delete(jobs)
            Files.move(backup, jobs)
            val reopened = FileStore(dir)
            val stored = reopened.listJobsByOwner(owner).single()
            val recovered = spaces(reopened, limits).submit(owner, request)
            assertEquals(stored.jobId, recovered.jobId)
            assertEquals(1, reopened.listJobsByOwner(owner).size)
            val budget = Quotas(reopened, limits)
            assertThrows(ApiException::class.java) { budget.consumeCompile(owner) }
            budget.consumeCompile("second owner")
            assertThrows(ApiException::class.java) { budget.consumeCompile("global limit reached") }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a reopened file store keeps revision members and original request identity`() {
        val dir = Files.createTempDirectory("a2g-space-restart")
        try {
            val first = spaces(FileStore(dir))
            val space = first.create("owner", CreateSpaceRequest("Before", "s"))
            val request = RenameSpaceRequest("After", space.revision, "edit")
            val changed = first.rename("owner", space.spaceId, request)
            val reopened = spaces(FileStore(dir))
            assertEquals(listOf(changed), reopened.list("owner"))
            assertEquals(changed, reopened.rename("owner", space.spaceId, request))
            assertThrows(ApiException::class.java) { reopened.rename("owner", space.spaceId, request.copy(title = "Different")) }
        } finally { dir.toFile().deleteRecursively() }
    }
}
class PostgresActivityStoreTest : ActivityStoreTest() { override fun newBackend() = PostgresBackend() }

class GenerationMigrationTest {
    @Test fun `V2 queued jobs keep compile identity and run beside documentless work after V3 upgrade`() {
        PostgresContainer.newDatabase().use { db ->
            val config = db.config()
            Flyway.configure().dataSource(db.url, config.user, config.password.reveal()).target("2").load().migrate()
            DriverManager.getConnection(db.url, config.user, config.password.reveal()).use { c -> c.createStatement().use { sql ->
                sql.executeUpdate("""INSERT INTO documents VALUES ('doc_old','owner','old','text','v1',1,1,'[]')""")
                sql.executeUpdate("""INSERT INTO jobs(job_id, owner_id, document_id, document_version, options, client_request_id,
                    package_id,state,created_at,updated_at) VALUES ('job_old','owner','doc_old','v1','{}','key','pkg_old','queued',1,1)""")
            } }
            PostgresStore.connect(config).use { upgraded ->
                assertEquals("doc_old", upgraded.getJob("job_old")!!.toStatus().documentId)
                val game = spaces(upgraded).submit("owner", GenerationRequest(GenerationInput.ConceptGame("binary search"), "key"))
                assertNull(upgraded.getJob(game.jobId)!!.documentId)
                assertEquals("job_old", upgraded.claimNext("w", 1000, 10)!!.jobId)
            }
        }
    }
}
