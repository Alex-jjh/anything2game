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
        override suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String, targetPosition: Int?) = cache.session.copy(revision = cache.session.revision + 1, messages = listOf(TutorMessage("late", "tutor", "Late result")))
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
                override suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String, targetPosition: Int?): TutorSession {
                    withContext(NonCancellable) { gate.await() }
                    return super.turn(cache, intent, question, requestId, targetPosition)
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
            assertNull(vm.state.value.cache!!.replyIntent)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `failed example keeps the previous reply label and retry labels only the saved new reply`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = initial().let { it.copy(
                session = it.session.copy(answers = mapOf("q" to UNKNOWN_ANSWER),
                    assessment = listOf(ConceptAssessment("q", "c", Understanding.NOT_YET_ASSESSED)),
                    messages = listOf(TutorMessage("old", "tutor", "Previous rephrase"))),
                lastReply = TutorReplyPresentation("old", TutorIntent.REPHRASE),
            ) }
            var fail = true
            val repo = object : Repository(base) {
                override suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String, targetPosition: Int?): TutorSession {
                    if (fail) throw java.io.IOException("offline")
                    return super.turn(cache, intent, question, requestId, targetPosition)
                }
            }
            val vm = TutorViewModel(repo, "tut_test"); runCurrent()
            vm.turn(TutorIntent.EXAMPLE); advanceUntilIdle()
            assertEquals("CONNECTION_OR_STORAGE", vm.state.value.problem)
            assertEquals(TutorIntent.REPHRASE, vm.state.value.cache!!.replyIntent)
            assertEquals(base, repo.cache)
            fail = false; vm.retry(); advanceUntilIdle()
            assertNull(vm.state.value.problem)
            assertEquals(TutorIntent.EXAMPLE, vm.state.value.cache!!.replyIntent)
            assertEquals(TutorIntent.EXAMPLE, repo.cache.replyIntent)
            vm.reload(); advanceUntilIdle()
            assertEquals(TutorIntent.EXAMPLE, vm.state.value.cache!!.replyIntent)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `answer choice is persisted and incomplete diagnostic cannot advance`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository(initial()); val vm = TutorViewModel(repo, "tut_test"); runCurrent()
            vm.next(); runCurrent(); assertTrue(vm.state.value.cache!!.session.assessment.isEmpty())
            // With one question, its answer is also the last one: it is kept and the starting point is assessed right away.
            vm.choose(UNKNOWN_ANSWER); advanceUntilIdle()
            assertEquals(mapOf("q" to UNKNOWN_ANSWER), repo.cache.draftAnswers)
            assertEquals(Understanding.NOT_YET_ASSESSED, vm.state.value.cache!!.session.assessment.single().evidence)
        } finally { Dispatchers.resetMain() }
    }
    private fun three(): TutorCache = initial().let { c ->
        val q = c.session.questions.single()
        c.copy(session = c.session.copy(questions = listOf(q, q.copy(id = "q2", conceptId = "c2"), q.copy(id = "q3", conceptId = "c3"))))
    }
    private class ThreeRepo(cache: TutorCache) : Repository(cache) {
        override suspend fun assess(cache: TutorCache, requestId: String) = cache.session.copy(revision = 1, answers = cache.draftAnswers,
            assessment = TutorRules.assess(TutorBlueprint(concepts = cache.session.questions.map { TutorConcept(it, "a", "e", "s", "x") }), cache.draftAnswers))
    }
    @Test fun `choosing an answer moves to the next question and the last answer assesses`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = ThreeRepo(three()); val vm = TutorViewModel(repo, "tut_test"); runCurrent()
            vm.choose("a"); advanceUntilIdle()
            assertEquals(1, vm.state.value.cache!!.questionIndex)
            assertEquals(mapOf("q" to "a"), vm.state.value.cache!!.draftAnswers)
            vm.previous(); advanceUntilIdle()                                   // go back: the earlier answer is still there, and can be changed
            assertEquals(0, vm.state.value.cache!!.questionIndex)
            assertEquals("a", vm.state.value.cache!!.draftAnswers["q"])
            vm.choose("b"); advanceUntilIdle()
            assertEquals(1, vm.state.value.cache!!.questionIndex); assertEquals("b", vm.state.value.cache!!.draftAnswers["q"])
            assertTrue(vm.state.value.cache!!.session.assessment.isEmpty())
            vm.choose(UNKNOWN_ANSWER); advanceUntilIdle()
            assertEquals(2, vm.state.value.cache!!.questionIndex); assertTrue(vm.state.value.cache!!.session.assessment.isEmpty())
            vm.choose("a"); advanceUntilIdle()                                   // the last answer: starting points, no further tap
            val done = vm.state.value.cache!!
            assertEquals(3, done.session.assessment.size)
            assertEquals(listOf(Understanding.DEMONSTRATED, Understanding.NEEDS_CLARIFICATION, Understanding.NOT_YET_ASSESSED).sorted(), done.session.assessment.map { it.evidence }.sorted())
            assertEquals(done, repo.cache)
            vm.choose("a"); advanceUntilIdle()                                   // answering after the assessment changes nothing
            assertEquals(done, vm.state.value.cache)
            assertFalse("the assessed session was saved with all three answers", repo.saved.any { it.session.assessment.isNotEmpty() && it.draftAnswers.size < 3 })
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
    @Test fun `the kept PDF of the session's document version is offered to Show source, and nothing without one`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val kept = SourceDocument("doc", "v", 1, java.io.File("doc.pdf"), DocumentLayout())
            val asked = mutableListOf<Pair<String, String>>()
            val repo = object : Repository(initial()) {
                override suspend fun sourceDocument(documentId: String, documentVersion: String) = kept.also { asked += documentId to documentVersion }
            }
            val vm = TutorViewModel(repo, "tut_test"); advanceUntilIdle()
            assertSame(kept, vm.sourceDocument.value)
            assertEquals(listOf("doc" to "v"), asked)
            val plain = TutorViewModel(Repository(initial()), "tut_test"); advanceUntilIdle()
            assertNull(plain.sourceDocument.value)
        } finally { Dispatchers.resetMain() }
    }
}
