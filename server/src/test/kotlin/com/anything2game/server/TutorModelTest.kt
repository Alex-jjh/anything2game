package com.anything2game.server

import com.anything2game.contract.*
import com.anything2game.contract.tutor.*
import com.anything2game.server.config.Provider
import com.anything2game.server.llm.*
import com.anything2game.server.store.StoredDocument
import com.anything2game.server.tutor.TutorService
import com.anything2game.server.api.ApiException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TutorModelTest {
    private val json = ContractJson.json
    private fun blueprint() = TutorBlueprint(concepts = listOf(TutorConcept(
        TutorQuestion("q1", "recall", "Recall", "Which activity retrieves memory?", listOf(TutorOption("a", "Recall"), TutorOption("b", "Reread")), listOf(SourceRef("c2", "Retrieval practice means pulling information out of memory"))),
        "a", "Retrieve from memory", "Recall before looking", "Close the notes and recall")))
    private fun document(env: TestEnv): StoredDocument = StoredDocument("doc_test", "owner", "Demo", DocumentKind.TEXT, "v1", 2, 1, Examples.retrieval.sources).also(env.store::saveDocument)

    @Test fun `model sees assessment and prior conversation and invalid citations are refused`() = runBlocking {
        val calls = mutableListOf<LlmRequest>()
        var invalid = false
        val model = object : LlmClient {
            override val modelId = "controlled-test-double"
            override suspend fun completeJson(request: LlmRequest): String {
                calls += request
                return if (request.label == "diagnostic") json.encodeToString(TutorBlueprint.serializer(), blueprint())
                else if (invalid) """{"text":"Unfounded claim","grounded":true,"sourceRefs":[{"chunkId":"c2","quote":"invented"}]}"""
                else """{"text":"Recall retrieves information from memory.","grounded":true,"sourceRefs":[{"chunkId":"c2","quote":"pulling information out of memory"}]}"""
            }
        }
        TestEnv().use { env -> runBlocking {
            val service = TutorService(env.store, model, model, false, 1000)
            val doc = document(env)
            var s = service.create(doc, CreateTutorRequest("en", "create")) {}
            s = service.assess(s.sessionId, "owner", AssessTutorRequest(0, mapOf("q1" to UNKNOWN_ANSWER), "assess"))
            s = service.turn(s.sessionId, "owner", TutorTurnRequest(s.revision, TutorIntent.START, clientRequestId = "start")) {}
            assertEquals("Recall retrieves information from memory.", s.messages.last().text)
            assertTrue(calls.last().user.contains("NOT_YET_ASSESSED"))
            assertTrue(calls.last().user.contains(UNKNOWN_ANSWER))
            invalid = true
            s = service.turn(s.sessionId, "owner", TutorTurnRequest(s.revision, TutorIntent.QUESTION, "Does this transfer?", "question")) {}
            assertTrue(calls.last().user.contains("Recall retrieves information from memory."))
            assertTrue(calls.last().user.contains("Does this transfer?"))
            assertFalse(s.messages.last().text.contains("Unfounded"))
            assertTrue(s.messages.last().sourceRefs.isEmpty())
        } }
    }
    @Test fun `late noncancellable provider response loses to cancellation`() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val model = object : LlmClient {
            override val modelId = "gated-test-double"
            override suspend fun completeJson(request: LlmRequest): String {
                if (request.label == "diagnostic") return json.encodeToString(TutorBlueprint.serializer(), blueprint())
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return """{"text":"Recall from memory","grounded":true,"sourceRefs":[{"chunkId":"c2","quote":"pulling information out of memory"}]}"""
            }
        }
        TestEnv().use { env -> runBlocking {
            val service = TutorService(env.store, model, model, false, 5000)
            var s = service.create(document(env), CreateTutorRequest("en", "create")) {}
            s = service.assess(s.sessionId, "owner", AssessTutorRequest(0, mapOf("q1" to "a"), "assess"))
            val request = TutorTurnRequest(s.revision, TutorIntent.START, clientRequestId = "slow")
            val result = async { runCatching { service.turn(s.sessionId, "owner", request) {} } }
            started.await()
            val cancelled = service.cancel(s.sessionId, "owner", "slow")
            release.complete(Unit)
            assertTrue(result.await().isFailure)
            assertEquals(cancelled, env.store.getTutor(s.sessionId)!!.session)
            assertTrue(cancelled.messages.isEmpty())
        } }
    }
    @Test fun `bad diagnostic and timeout never create a session`() = runBlocking {
        TestEnv().use { env -> runBlocking {
            val doc = document(env)
            val broken = object : LlmClient { override val modelId = "bad"; override suspend fun completeJson(request: LlmRequest) = "{}" }
            assertTrue(runCatching { TutorService(env.store, broken, broken, false, 100).create(doc, CreateTutorRequest(clientRequestId = "bad")) {} }.isFailure)
            val slow = SlowLlm(200)
            val failure = runCatching { TutorService(env.store, slow, slow, false, 20).create(doc, CreateTutorRequest(clientRequestId = "slow")) {} }.exceptionOrNull()
            assertEquals(ErrorCodes.MODEL_TIMEOUT, (failure as com.anything2game.server.compile.GenerationException).code)
        } }
    }
    @Test fun `mock session cannot silently switch to paid model calls after configuration changes`() = runBlocking {
        TestEnv().use { env -> runBlocking {
            val forbidden = object : LlmClient {
                override val modelId = "never-call"
                override suspend fun completeJson(request: LlmRequest): String = error("A mock session must stay mock")
            }
            val first = TutorService(env.store, forbidden, forbidden, true, 1000)
            var s = first.create(document(env), CreateTutorRequest(clientRequestId = "create")) {}
            s = first.assess(s.sessionId, "owner", AssessTutorRequest(0, s.questions.associate { it.id to UNKNOWN_ANSWER }, "assess"))
            val reconfigured = TutorService(env.store, forbidden, forbidden, false, 1000)
            val next = reconfigured.turn(s.sessionId, "owner", TutorTurnRequest(s.revision, TutorIntent.START, clientRequestId = "start")) {}
            assertEquals("mock", next.mode)
            assertEquals(2, next.messages.size)
        } }
    }

}
