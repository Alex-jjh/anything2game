package com.anything2game.server

import com.anything2game.contract.CompileJobState
import com.anything2game.server.compile.PublishInput
import com.anything2game.server.compile.PublishNode
import com.anything2game.server.config.Limits
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.llm.FakeLlmClient
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.LlmRequest
import com.anything2game.server.llm.LlmRouter
import com.anything2game.server.store.JobRecord
import com.anything2game.server.store.Store
import com.anything2game.server.store.StoredPackage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Adds a delay to every model call, so a worker is busy long enough for the other one to take jobs too. */
private class DelayedLlm(private val ms: Long, private val delegate: LlmClient = FakeLlmClient()) : LlmClient {
    override val modelId get() = delegate.modelId
    override suspend fun completeJson(request: LlmRequest): String { delay(ms); return delegate.completeJson(request) }
}

/**
 * Several server instances (AppContext = one instance) on one storage: the queue, the leases and the races, on both backends.
 * For the file store the instances share one object, which is its single-instance model.
 */
abstract class JobQueueTest : StoreBackendTest() {
    private val contexts = mutableListOf<AppContext>()

    @After fun stopInstances() { contexts.forEach { runCatching { it.close() } } }

    /** A server instance on [on] with its own worker id and model; not started. */
    private fun instance(
        workerId: String, llm: LlmClient = FakeLlmClient(), on: Store = store, leaseMillis: Long = 30_000, concurrency: Int = 2, limits: Limits = Limits(),
    ): AppContext {
        val config = ServerConfig(limits = limits, workerLeaseMillis = leaseMillis, workerPollMillis = 50, workerConcurrency = concurrency)
        return AppContext(config, on, LlmRouter(llm, llm, llm), jwtAuthenticator = null, workerId = workerId).also { contexts += it }
    }

    private fun awaitJobs(ids: List<String>, timeoutMs: Long = 60_000, until: (JobRecord) -> Boolean = { it.state.isTerminal }): List<JobRecord> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val jobs = ids.map { store.getJob(it)!! }
            if (jobs.all(until)) return jobs
            check(System.currentTimeMillis() < deadline) { "jobs not done: ${jobs.map { it.state }}" }
            Thread.sleep(25)
        }
    }

    @Test fun `two workers on one database process N jobs and no job is processed twice`() {
        val llm = RecordingLlm(DelayedLlm(60))
        val doc = newDocument()
        val ids = (1..10).map { store.createJob(newJob(doc, createdAt = it.toLong())).jobId }
        val a = instance("worker-a", llm, on = anotherInstance(), concurrency = 1)
        val b = instance("worker-b", llm, on = anotherInstance(), concurrency = 1)
        a.start(); b.start()
        val done = awaitJobs(ids)
        assertTrue(done.all { it.state == CompileJobState.READY })
        assertEquals("every job was claimed exactly once", List(10) { 1 }, done.map { it.attempts })
        assertEquals("the plan step ran once per job, never twice", 10, llm.count("plan"))
        assertEquals("both workers took part", setOf("worker-a", "worker-b"), done.map { it.claimedBy }.toSet())
        assertTrue(done.all { store.getPackage(it.packageId)!!.complete })
    }

    @Test fun `when a worker dies its lease expires and another worker takes the job over`() {
        val llm = RecordingLlm()
        val doc = newDocument()
        val job = store.createJob(newJob(doc))
        // "dead" claimed the job, started planning and published level 1, then the process vanished
        val claimed = store.claimNext("dead", leaseMillis = 400, now = System.currentTimeMillis())!!
        store.updateJob(claimed.jobId) { it.copy(state = CompileJobState.GENERATING, levelsPlanned = 5, levelsReady = 1) }
        store.savePackage(StoredPackage(doc.ownerId, job.jobId, false, Examples.retrieval.copy(packageId = job.packageId, documentId = doc.documentId)))

        val live = instance("alive", llm, leaseMillis = 400)
        live.start()
        val done = awaitJobs(listOf(job.jobId)).single()
        assertEquals(CompileJobState.READY, done.state)
        assertEquals("alive", done.claimedBy)
        assertEquals(2, done.attempts)
        assertEquals(done.levelsPlanned, done.levelsReady)
        assertEquals("the half-built package was discarded and rebuilt", 1, llm.count("plan"))
        assertTrue(store.getPackage(job.packageId)!!.complete)
    }

    @Test fun `a worker that keeps renewing its lease is not robbed while it works`() {
        val llm = GatedLlm()
        val rec = RecordingLlm(llm)
        val doc = newDocument()
        val job = store.createJob(newJob(doc))
        val a = instance("worker-a", rec, on = anotherInstance(), leaseMillis = 300)
        val b = instance("worker-b", rec, on = anotherInstance(), leaseMillis = 300)
        a.start()
        runBlocking { llm.started.await() }
        b.start()
        Thread.sleep(1_500) // five lease periods: only renewal keeps the job with A
        assertEquals("worker-a", store.getJob(job.jobId)!!.claimedBy)
        assertEquals(1, rec.count("plan"))
        llm.gate.complete(Unit)
        val done = awaitJobs(listOf(job.jobId)).single()
        assertEquals(CompileJobState.READY, done.state)
        assertEquals(1, done.attempts)
    }

    @Test fun `cancelling on another instance stops the worker and its late result is discarded`() {
        val llm = GatedLlm()
        val doc = newDocument()
        val job = store.createJob(newJob(doc))
        val a = instance("worker-a", llm, on = anotherInstance(), leaseMillis = 300)
        val b = instance("worker-b", on = anotherInstance(), leaseMillis = 300)
        a.start()
        runBlocking { llm.started.await() }
        val cancelled = b.runner.cancel(job.jobId)!!
        assertEquals(CompileJobState.CANCELLED, cancelled.state)
        llm.gate.complete(Unit)
        Thread.sleep(800)
        val after = store.getJob(job.jobId)!!
        assertEquals(CompileJobState.CANCELLED, after.state)
        assertEquals(0, after.levelsReady)
        assertNull(store.getPackage(job.packageId))
    }

    @Test fun `a job that keeps killing its worker is failed instead of retried forever`() {
        val doc = newDocument()
        val job = store.createJob(newJob(doc))
        repeat(4) { i -> store.claimNext("crasher-$i", leaseMillis = 1, now = i * 10L) } // four claims, no result
        val live = instance("alive")
        live.start()
        val done = awaitJobs(listOf(job.jobId)).single()
        assertEquals(CompileJobState.FAILED, done.state)
        assertEquals("GENERATION_FAILED", done.error!!.code)
    }

    // ---- cancel and publish race -------------------------------------------------------------------------------

    private fun levelOne() = Examples.retrieval.levels.first()
    private fun shellFor(job: JobRecord) = Examples.retrieval.copy(packageId = job.packageId, documentId = requireNotNull(job.documentId), levels = emptyList())

    @Test fun `a result that arrives after cancel is discarded by the store`() {
        val doc = newDocument()
        val job = store.createJob(newJob(doc))
        val claimed = store.claimNext("w1", 60_000, System.currentTimeMillis())!!
        val ctx = instance("w1")
        ctx.runner.cancel(claimed.jobId)
        val published = runBlocking { PublishNode(store, store, "w1").run(PublishInput(job.jobId, doc.ownerId, levelOne(), shellFor(job))) }
        assertFalse(published)
        assertNull(store.getPackage(job.packageId))
        assertEquals(CompileJobState.CANCELLED, store.getJob(job.jobId)!!.state)
    }

    @Test fun `a compile lease that expired before takeover cannot publish levels`() {
        val doc = newDocument()
        val job = store.createJob(newJob(doc))
        store.claimNext("expired", 10, 100)
        val published = runBlocking { PublishNode(store, store, "expired", clock = { 111 }).run(
            PublishInput(job.jobId, doc.ownerId, levelOne(), shellFor(job))) }
        assertFalse(published)
        assertNull(store.getPackage(job.packageId))
    }

    @Test fun `a worker that lost its lease cannot publish`() {
        val doc = newDocument()
        val job = store.createJob(newJob(doc))
        store.claimNext("other", 60_000, System.currentTimeMillis())
        val published = runBlocking { PublishNode(store, store, "me").run(PublishInput(job.jobId, doc.ownerId, levelOne(), shellFor(job))) }
        assertFalse(published)
        assertNull(store.getPackage(job.packageId))
    }

    @Test fun `cancel racing with publish never leaves a cancelled job with an unfinished package`() {
        val rounds = 25
        val pool = Executors.newFixedThreadPool(4)
        val ctxB = instance("w1-cancel", on = anotherInstance())
        var publishedFirst = 0
        var cancelledFirst = 0
        repeat(rounds) { round ->
            val doc = newDocument(id = "doc_race_$round")
            val job = store.createJob(newJob(doc))
            store.claimNext("w1", 60_000, System.currentTimeMillis())
            val go = CountDownLatch(1)
            val published = AtomicInteger()
            val publish = pool.submit {
                go.await()
                if (runBlocking { PublishNode(store, store, "w1").run(PublishInput(job.jobId, doc.ownerId, levelOne(), shellFor(job))) }) published.incrementAndGet()
            }
            val cancel = pool.submit { go.await(); ctxB.runner.cancel(job.jobId) }
            go.countDown()
            publish.get(30, TimeUnit.SECONDS); cancel.get(30, TimeUnit.SECONDS)

            val finalJob = store.getJob(job.jobId)!!
            val pkg = store.getPackage(job.packageId)
            assertEquals(CompileJobState.CANCELLED, finalJob.state)
            if (published.get() == 1) {
                publishedFirst++
                assertNotNull("a level that was accepted is in the package", pkg)
                assertTrue("and the cancel completed the package", pkg!!.complete)
                assertEquals(1, finalJob.levelsReady)
            } else {
                cancelledFirst++
                assertNull("a refused level is not in the store", pkg)
                assertEquals(0, finalJob.levelsReady)
            }
        }
        pool.shutdown()
        assertEquals(rounds, publishedFirst + cancelledFirst)
    }

    // ---- cost guards across instances --------------------------------------------------------------------------

    @Test fun `the global compile cap and the install limit hold across two server instances`() {
        val limits = Limits(globalCompilesPerDay = 3, installsPerHour = 2, compilesPerInstallPerDay = 100)
        val a = instance("a", on = anotherInstance(), limits = limits)
        val b = instance("b", on = anotherInstance(), limits = limits)
        a.quotas.consumeCompile("install:1")
        b.quotas.consumeCompile("install:2")
        a.quotas.consumeCompile("install:3")
        for (q in listOf(a.quotas, b.quotas)) {
            try { q.consumeCompile("install:4"); throw AssertionError("expected the cap to hold") } catch (e: com.anything2game.server.api.ApiException) { assertEquals(429, e.status.value) }
        }
        a.quotas.checkInstallCreation()
        b.quotas.checkInstallCreation()
        for (q in listOf(a.quotas, b.quotas)) {
            try { q.checkInstallCreation(); throw AssertionError("expected the limit to hold") } catch (e: com.anything2game.server.api.ApiException) { assertEquals(429, e.status.value) }
        }
    }
}

class FileStoreJobQueueTest : JobQueueTest() {
    override fun newBackend(): TestBackend = FileBackend()
}

class PostgresStoreJobQueueTest : JobQueueTest() {
    override fun newBackend(): TestBackend = PostgresBackend()
}
