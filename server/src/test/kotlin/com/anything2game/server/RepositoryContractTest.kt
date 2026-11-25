package com.anything2game.server

import com.anything2game.contract.tutor.TutorMode

import com.anything2game.contract.ApiError
import com.anything2game.contract.CompileJobState
import com.anything2game.server.store.InstallRecord
import com.anything2game.server.store.StoredPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What every storage backend must do. Run against FileStore and PostgresStore (below); the Postgres class is skipped when Docker is unavailable.
 * "Another instance" is a second server process on the same database; for the file store it is the same object (single instance by design).
 */
abstract class RepositoryContractTest : StoreBackendTest() {

    @Test fun `tutor snapshots are versioned atomically and cascade with their document`() {
        val doc = newDocument()
        val source = doc.chunks.first()
        val q = com.anything2game.contract.tutor.TutorQuestion("q", "c", "Concept", "Question", listOf(
            com.anything2game.contract.tutor.TutorOption("a", "A"), com.anything2game.contract.tutor.TutorOption("b", "B")),
            listOf(com.anything2game.contract.SourceRef(source.chunkId, source.text.take(80))))
        val blueprint = com.anything2game.contract.tutor.TutorBlueprint(concepts = listOf(com.anything2game.contract.tutor.TutorConcept(q, "a", "Explanation", "Simple", "Example")))
        val session = com.anything2game.contract.tutor.TutorSession(sessionId = "tut_test", documentId = doc.documentId, documentVersion = doc.documentVersion,
            title = doc.title, language = "en", mode = TutorMode.MOCK, questions = listOf(q), sources = doc.chunks)
        val first = com.anything2game.server.tutor.TutorRecord(doc.ownerId, blueprint, session)
        assertEquals(first, store.createTutor(first))
        assertEquals(first, anotherInstance().createTutor(first.copy(session = session.copy(title = "must not replace"))))
        val next = first.copy(session = session.copy(revision = 1))
        assertTrue(store.saveTutorIfRevision(next, 0))
        assertFalse(anotherInstance().saveTutorIfRevision(first, 0))
        assertEquals(next, anotherInstance().getTutor("tut_test"))
        store.deleteDocument(doc.documentId)
        assertNull(store.getTutor("tut_test"))
        assertNull(store.createTutor(first))
    }

    // ---- installs and counters --------------------------------------------------------------------------------

    @Test fun `installs are found by token hash`() {
        val install = InstallRecord("ins_1", "hash1", 5L)
        store.createInstall(install)
        assertEquals(install, store.findInstallByTokenHash("hash1"))
        assertNull(store.findInstallByTokenHash("nope"))
        assertEquals(install, anotherInstance().findInstallByTokenHash("hash1"))
    }

    @Test fun `per-owner counters stop at the limit and are separate per day and owner`() {
        assertTrue(store.tryConsumeQuota("install:a", "compile", "2026-11-17", 2))
        assertTrue(store.tryConsumeQuota("install:a", "compile", "2026-11-17", 2))
        assertFalse(store.tryConsumeQuota("install:a", "compile", "2026-11-17", 2))
        assertTrue(store.tryConsumeQuota("install:a", "compile", "2026-11-18", 2))
        assertTrue(store.tryConsumeQuota("user:b", "compile", "2026-11-17", 2))
        assertTrue(store.tryConsumeQuota("install:a", "ask", "2026-11-17", 1))
        assertFalse(store.tryConsumeQuota("install:a", "ask", "2026-11-17", 0))
    }

    @Test fun `global counters hold across two instances, also when used concurrently`() {
        val a = store
        val b = anotherInstance()
        val granted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        repeat(60) { i ->
            pool.submit {
                start.await()
                if ((if (i % 2 == 0) a else b).tryConsumeGlobal("compile", "2026-11-18", 25)) granted.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals("exactly the limit is granted, never more", 25, granted.get())
        assertFalse(a.tryConsumeGlobal("compile", "2026-11-18", 25))
        assertFalse(b.tryConsumeGlobal("compile", "2026-11-18", 25))
        assertTrue("another window starts at zero", b.tryConsumeGlobal("compile", "2026-11-19", 25))
    }

    @Test fun `purged counters start again`() {
        assertTrue(store.tryConsumeGlobal("installs", "h1", 1))
        assertFalse(store.tryConsumeGlobal("installs", "h1", 1))
        store.purgeCounters(System.currentTimeMillis() + 60_000)
        assertTrue(store.tryConsumeGlobal("installs", "h1", 1))
    }

    // ---- documents, packages, frames ----------------------------------------------------------------------------

    @Test fun `a document round-trips with its chunks`() {
        val doc = newDocument()
        assertEquals(doc, store.getDocument(doc.documentId))
        assertEquals(doc, anotherInstance().getDocument(doc.documentId))
        assertNull(store.getDocument("doc_missing"))
    }

    @Test fun `packages and frames round-trip and deleting a package takes its frames`() {
        val doc = newDocument()
        val job = newJob(doc).also { store.saveJob(it) }
        val pkg = Examples.retrieval.copy(packageId = job.packageId, documentId = doc.documentId)
        val stored = StoredPackage(doc.ownerId, job.jobId, complete = false, pkg = pkg)
        store.savePackage(stored)
        assertEquals(stored, store.getPackage(pkg.packageId))
        store.savePackage(stored.copy(complete = true))
        assertTrue(store.getPackage(pkg.packageId)!!.complete)

        val frame = frameFor(pkg.packageId)
        store.saveFrame(frame)
        assertEquals(frame, store.getFrame(pkg.packageId, frame.frameId))
        store.deletePackage(pkg.packageId)
        assertNull(store.getPackage(pkg.packageId))
        assertNull(store.getFrame(pkg.packageId, frame.frameId))
        store.saveFrame(frame) // the package is gone: nothing is resurrected
        assertNull(store.getFrame(pkg.packageId, frame.frameId))
    }

    @Test fun `deleting a document removes its jobs, packages and frames and nothing else`() {
        val doc = newDocument()
        val other = newDocument(owner = "user:other")
        val job = newJob(doc).also { store.saveJob(it) }
        val otherJob = newJob(other).also { store.saveJob(it) }
        val pkg = Examples.retrieval.copy(packageId = job.packageId, documentId = doc.documentId)
        store.savePackage(StoredPackage(doc.ownerId, job.jobId, true, pkg))
        store.saveFrame(frameFor(pkg.packageId))
        store.deleteDocument(doc.documentId)
        store.deleteDocument(doc.documentId) // idempotent
        assertNull(store.getDocument(doc.documentId))
        assertNull(store.getJob(job.jobId))
        assertNull(store.getPackage(pkg.packageId))
        assertEquals(emptyList<Any>(), store.listJobsByDocument(doc.documentId))
        assertNotNull(store.getJob(otherJob.jobId))
        assertNotNull(store.getDocument(other.documentId))
    }

    // ---- jobs -----------------------------------------------------------------------------------------------

    @Test fun `createJob is idempotent on owner and clientRequestId`() {
        val doc = newDocument()
        val first = store.createJob(newJob(doc, clientRequestId = "req-1"))
        val second = store.createJob(newJob(doc, clientRequestId = "req-1"))
        assertEquals(first.jobId, second.jobId)
        assertEquals(1, store.listJobsByOwner(doc.ownerId).size)
        val third = store.createJob(newJob(doc, clientRequestId = "req-2"))
        assertEquals(setOf(first.jobId, third.jobId), store.listJobsByOwner(doc.ownerId).map { it.jobId }.toSet())
        assertEquals(2, store.listJobsByDocument(doc.documentId).size)
        assertEquals(emptyList<Any>(), store.listJobsByOwner("install:nobody"))
    }

    @Test fun `a job round-trips with its error and lease`() {
        val doc = newDocument()
        val job = newJob(doc).copy(
            state = CompileJobState.FAILED, error = ApiError("GENERATION_FAILED", "x", true), levelsReady = 1, levelsPlanned = 3,
            claimedBy = "w1", leaseUntil = 99L, attempts = 2,
        )
        store.saveJob(job)
        assertEquals(job, store.getJob(job.jobId))
        assertEquals(job, anotherInstance().getJob(job.jobId))
        store.saveJob(job.copy(levelsReady = 2))
        assertEquals(2, store.getJob(job.jobId)!!.levelsReady)
    }

    @Test fun `updateIfNotTerminal changes a live job and leaves a terminal one alone`() {
        val doc = newDocument()
        val job = newJob(doc).also { store.saveJob(it) }
        val live = store.updateIfNotTerminal(job.jobId) { it.copy(state = CompileJobState.PLANNING, updatedAt = 7) }!!
        assertEquals(CompileJobState.PLANNING, live.state)
        store.updateIfNotTerminal(job.jobId) { it.copy(state = CompileJobState.CANCELLED) }
        var called = false
        val after = store.updateIfNotTerminal(job.jobId) { called = true; it.copy(state = CompileJobState.READY) }!!
        assertFalse("the transform is not even run for a terminal job", called)
        assertEquals(CompileJobState.CANCELLED, after.state)
        assertEquals(CompileJobState.CANCELLED, store.getJob(job.jobId)!!.state)
        assertNull(store.updateIfNotTerminal("job_missing") { it })
        // updateJob is the unconditional variant
        assertEquals(CompileJobState.QUEUED, store.updateJob(job.jobId) { it.copy(state = CompileJobState.QUEUED) }!!.state)
    }

    @Test fun `concurrent updates of one job are serialised`() {
        val doc = newDocument()
        val job = newJob(doc).also { store.saveJob(it) }
        val instances = listOf(store, anotherInstance())
        val pool = Executors.newFixedThreadPool(8)
        repeat(40) { i -> pool.submit { instances[i % 2].updateJob(job.jobId) { it.copy(levelsReady = it.levelsReady + 1) } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(40, store.getJob(job.jobId)!!.levelsReady)
    }

    // ---- the queue ----------------------------------------------------------------------------------------------

    @Test fun `claimNext hands out the oldest job first and only once per lease`() {
        val doc = newDocument()
        val j2 = newJob(doc, createdAt = 200).also { store.saveJob(it) }
        val j1 = newJob(doc, createdAt = 100).also { store.saveJob(it) }
        val done = newJob(doc, createdAt = 50).copy(state = CompileJobState.READY).also { store.saveJob(it) }
        val first = store.claimNext("w1", leaseMillis = 1_000, now = 1_000)!!
        assertEquals(j1.jobId, first.jobId)
        assertEquals("w1", first.claimedBy)
        assertEquals(2_000L, first.leaseUntil)
        assertEquals(1, first.attempts)
        assertEquals(CompileJobState.QUEUED, first.state)
        assertEquals(j2.jobId, store.claimNext("w2", 1_000, 1_000)!!.jobId)
        assertNull("both are leased, the finished one is never claimed", store.claimNext("w3", 1_000, 1_500))
        assertEquals(done.state, store.getJob(done.jobId)!!.state)
        assertEquals(first.copy(), store.getJob(j1.jobId))
    }

    @Test fun `an expired lease can be taken over, a renewed one cannot`() {
        val doc = newDocument()
        val job = newJob(doc).also { store.saveJob(it) }
        assertNotNull(store.claimNext("dead", 1_000, now = 0))
        assertNull(store.claimNext("alive", 1_000, now = 999))
        assertTrue(store.renewLease(job.jobId, "dead", 1_000, now = 900)) // lease now runs to 1_900
        assertNull(store.claimNext("alive", 1_000, now = 1_800))
        assertFalse("somebody else cannot renew", store.renewLease(job.jobId, "alive", 1_000, now = 1_000))
        val taken = store.claimNext("alive", 1_000, now = 1_901)!!
        assertEquals("alive", taken.claimedBy)
        assertEquals(2, taken.attempts)
        assertFalse("the old owner learns it lost the job", store.renewLease(job.jobId, "dead", 1_000, now = 1_902))
        store.updateJob(job.jobId) { it.copy(state = CompileJobState.CANCELLED) }
        assertFalse("a terminal job has nothing to renew", store.renewLease(job.jobId, "alive", 1_000, now = 1_903))
    }

    @Test fun `a released lease can be claimed at once`() {
        val doc = newDocument()
        val job = newJob(doc).also { store.saveJob(it) }
        assertNotNull(store.claimNext("w1", 60_000, now = 0))
        store.releaseLease(job.jobId, "w2") // not the owner: no effect
        assertNull(store.claimNext("w2", 60_000, now = 1))
        store.releaseLease(job.jobId, "w1")
        assertEquals(job.jobId, store.claimNext("w2", 60_000, now = 2)!!.jobId)
    }

    @Test fun `many claimers on two instances never get the same job`() {
        val doc = newDocument()
        val ids = (1..40).map { newJob(doc, createdAt = it.toLong()).also { j -> store.saveJob(j) }.jobId }
        val instances = listOf(store, anotherInstance())
        val claims = ConcurrentHashMap<String, String>()
        val duplicates = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(8) { t ->
            pool.submit {
                start.await()
                while (true) {
                    val job = instances[t % 2].claimNext("worker-$t", 60_000, now = 0) ?: break
                    if (claims.putIfAbsent(job.jobId, "worker-$t") != null) duplicates.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(0, duplicates.get())
        assertEquals(ids.toSet(), claims.keys)
    }
}

class FileStoreRepositoryTest : RepositoryContractTest() {
    override fun newBackend(): TestBackend = FileBackend()
}

class PostgresStoreRepositoryTest : RepositoryContractTest() {
    override fun newBackend(): TestBackend = PostgresBackend()

    @Test fun `the schema is created by Flyway and a second instance starting later migrates nothing twice`() {
        val second = anotherInstance()
        store.ping()
        second.ping()
        val doc = newDocument()
        assertEquals(doc, second.getDocument(doc.documentId))
    }

    @Test fun `NUL escapes from a model do not break jsonb storage`() {
        val doc = newDocument()
        val job = newJob(doc).also { store.saveJob(it) }
        val pkg = Examples.retrieval.copy(packageId = job.packageId, documentId = doc.documentId, title = "bad\u0000title")
        store.savePackage(StoredPackage(doc.ownerId, job.jobId, false, pkg))
        assertEquals("bad\uFFFDtitle", store.getPackage(pkg.packageId)!!.pkg.title)
    }
}
