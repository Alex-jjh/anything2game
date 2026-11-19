package com.anything2game.app.data.tutor

import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.importing.ImportViewModel
import com.anything2game.app.ui.reading.TutorViewModel
import com.anything2game.contract.*
import com.anything2game.contract.tutor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TutorStateTest {
    private fun initial(): TutorCache {
        val q = TutorQuestion("q", "c", "Concept", "Question", listOf(TutorOption("a", "A"), TutorOption("b", "B")), listOf(SourceRef("s", "source")))
        return TutorCache(TutorSession(sessionId = "tut_test", documentId = "doc", documentVersion = "v", title = "Title", language = "en", mode = "mock", questions = listOf(q), sources = listOf(SourceChunk("s", 0, "1", "source text"))))
    }
    private open class Repository(var cache: TutorCache) : TutorRepository {
        override val endpoint = "http://127.0.0.1:18080"
        override val localServer = true
        var uploads = 0; var creates = 0
        val saved = mutableListOf<TutorCache>()
        override suspend fun open(id: String) = OpenTutor(cache)
        override suspend fun save(cache: TutorCache) { this.cache = cache; saved += cache }
        override suspend fun list() = listOf(cache.session)
        override suspend fun assess(cache: TutorCache, requestId: String) = cache.session.copy(revision = 1, answers = cache.draftAnswers, assessment = listOf(ConceptAssessment("q", "c", Understanding.NOT_YET_ASSESSED)))
        override suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String) = cache.session.copy(revision = cache.session.revision + 1, messages = listOf(TutorMessage("late", "tutor", "Late result")))
        override suspend fun cancel(cache: TutorCache, requestId: String) = cache.session.copy(revision = cache.session.revision + 1)
        override suspend fun upload(request: ImportRequest): DocumentResponse { uploads++; return DocumentResponse("doc", "v", 1, "Title") }
        override suspend fun create(document: DocumentResponse, requestId: String): TutorSession { creates++; return cache.session }
        override suspend fun preparePractice(session: TutorSession): LearningPackage = error("Not used by tutor")
    }
    @Test fun `cancel rejects a late tutor reply while keeping existing assessment`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = initial().let { it.copy(session = it.session.copy(answers = mapOf("q" to UNKNOWN_ANSWER), assessment = listOf(ConceptAssessment("q", "c", Understanding.NOT_YET_ASSESSED)))) }
            val gate = CompletableDeferred<Unit>()
            val repo = object : Repository(base) {
                override suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String): TutorSession {
                    withContext(NonCancellable) { gate.await() }
                    return super.turn(cache, intent, question, requestId)
                }
            }
            val vm = TutorViewModel(repo, "tut_test"); runCurrent()
            vm.turn(TutorIntent.START); runCurrent()
            assertTrue(vm.state.value.busy)
            vm.cancelPending(); runCurrent()
            gate.complete(Unit); advanceUntilIdle()
            assertFalse(vm.state.value.busy)
            assertTrue(vm.state.value.cache!!.session.messages.isEmpty())
            assertEquals(base.session.assessment, vm.state.value.cache!!.session.assessment)
            assertFalse(repo.saved.any { it.session.messages.any { m -> m.id == "late" } })
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `answer choice is persisted and incomplete diagnostic cannot advance`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository(initial()); val vm = TutorViewModel(repo, "tut_test"); runCurrent()
            vm.next(); runCurrent(); assertTrue(vm.state.value.cache!!.session.assessment.isEmpty())
            vm.choose(UNKNOWN_ANSWER); advanceUntilIdle()
            assertEquals(mapOf("q" to UNKNOWN_ANSWER), repo.cache.draftAnswers)
            vm.next(); advanceUntilIdle()
            assertEquals(Understanding.NOT_YET_ASSESSED, vm.state.value.cache!!.session.assessment.single().evidence)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `import retry and process reconstruction reuse a completed upload`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = object : Repository(initial()) {
                override suspend fun create(document: DocumentResponse, requestId: String): TutorSession {
                    creates++
                    if (creates == 1) throw TutorApiException("MODEL_TIMEOUT", true)
                    return cache.session
                }
            }
            val handle = SavedStateHandle(); val req = ImportRequest.SharedText("Title", "source text")
            val vm = ImportViewModel(repo, req, handle); vm.start(); advanceUntilIdle()
            assertEquals("MODEL_TIMEOUT", vm.state.value.error)
            assertEquals(1, repo.uploads)
            val restored = ImportViewModel(repo, req, handle); restored.start(); advanceUntilIdle()
            assertEquals("tut_test", restored.state.value.ready)
            assertEquals(1, repo.uploads)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `cancelled import ignores delayed generation and does not install it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            val repo = object : Repository(initial()) {
                override suspend fun create(document: DocumentResponse, requestId: String): TutorSession {
                    withContext(NonCancellable) { gate.await() }
                    return cache.session
                }
            }
            val vm = ImportViewModel(repo, ImportRequest.SharedText("Title", "source text"), SavedStateHandle())
            vm.start(); runCurrent(); assertTrue(vm.state.value.uploaded)
            vm.cancel(); gate.complete(Unit); advanceUntilIdle()
            assertNull(vm.state.value.ready); assertTrue(repo.saved.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
