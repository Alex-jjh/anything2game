package com.anything2game.server

import com.anything2game.contract.*
import com.anything2game.contract.tutor.*
import io.ktor.http.*
import io.ktor.client.statement.bodyAsText
import org.junit.Assert.*
import org.junit.Test

class TutorApiTest {
    @Test fun `PDF diagnostic assessment dialogue resume and owner boundaries`() = TestEnv().use { env -> env.run {
        val api = api()
        val token = api.install().second
        val other = api.install().second
        val upload = api.uploadPdf(token, makePdf(pagesOf(Examples.retrieval).map { it.replace("\n", " ") }))
        assertEquals(HttpStatusCode.Created, upload.status)
        val doc = api.decode(DocumentResponse.serializer(), upload)
        val path = "/v1/documents/${doc.documentId}/tutor"
        val createBody = """{"language":"zh","clientRequestId":"start-1"}"""
        val r = api.send(HttpMethod.Post, path, token, createBody)
        assertEquals(r.bodyAsText(), HttpStatusCode.Created, r.status)
        var s = api.decode(TutorSession.serializer(), r)
        assertEquals(doc.documentVersion, s.documentVersion)
        assertEquals("mock", s.mode)
        assertTrue(s.questions.isNotEmpty())
        assertFalse("No scoring keys before assessment", r.bodyAsText().contains("correctOptionId"))
        s.questions.forEach { TutorRules.checkRefs(it.sourceRefs, s.sources) }
        assertEquals(s, api.decode(TutorSession.serializer(), api.send(HttpMethod.Post, path, token, createBody)))
        val tutorPath = "/v1/tutors/${s.sessionId}"
        assertEquals(HttpStatusCode.Unauthorized, api.send(HttpMethod.Get, tutorPath, null).status)
        assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, tutorPath, other).status)
        assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Post, path, other, createBody).status)
        assertEquals(HttpStatusCode.BadRequest, api.send(HttpMethod.Post, "$tutorPath/turns", token,
            """{"expectedRevision":0,"intent":"START","clientRequestId":"early"}""").status)
        assertEquals(HttpStatusCode.BadRequest, api.send(HttpMethod.Post, "$tutorPath/assessment", token,
            """{"expectedRevision":0,"answers":{},"clientRequestId":"incomplete"}""").status)
        val answers = s.questions.mapIndexed { i, q -> q.id to when(i) { 0 -> "source"; 1 -> "unsupported"; else -> UNKNOWN_ANSWER } }.toMap()
        val body = ContractJson.json.encodeToString(AssessTutorRequest.serializer(), AssessTutorRequest(0, answers, "answers-1"))
        val assessed = api.send(HttpMethod.Post, "$tutorPath/assessment", token, body)
        assertEquals(HttpStatusCode.OK, assessed.status)
        s = api.decode(TutorSession.serializer(), assessed)
        assertEquals(listOf(Understanding.DEMONSTRATED, Understanding.NEEDS_CLARIFICATION, Understanding.NOT_YET_ASSESSED).take(s.questions.size), s.assessment.map { it.evidence })
        assertEquals(s, api.decode(TutorSession.serializer(), api.send(HttpMethod.Post, "$tutorPath/assessment", token, body)))
        val turn = ContractJson.json.encodeToString(TutorTurnRequest.serializer(), TutorTurnRequest(s.revision, TutorIntent.START, clientRequestId = "turn-1"))
        val spoken = api.send(HttpMethod.Post, "$tutorPath/turns", token, turn)
        assertEquals(spoken.bodyAsText(), HttpStatusCode.OK, spoken.status)
        s = api.decode(TutorSession.serializer(), spoken)
        assertEquals(2, s.messages.size)
        assertTrue(s.messages.last().text.contains("澄清"))
        assertEquals(s, api.decode(TutorSession.serializer(), api.send(HttpMethod.Post, "$tutorPath/turns", token, turn)))
        assertEquals(HttpStatusCode.BadRequest, api.send(HttpMethod.Post, "$tutorPath/turns", token, turn.replace("START", "EXAMPLE")).status)
        assertEquals(HttpStatusCode.Conflict, api.send(HttpMethod.Post, "$tutorPath/turns", token, turn.replace("turn-1", "stale")).status)
        val question = ContractJson.json.encodeToString(TutorTurnRequest.serializer(), TutorTurnRequest(s.revision, TutorIntent.QUESTION, "What about transfer?", "follow-up"))
        s = api.decode(TutorSession.serializer(), api.send(HttpMethod.Post, "$tutorPath/turns", token, question))
        assertEquals(4, s.messages.size)
        assertTrue(s.messages.last().text.contains("没法回答新的问题"))
        assertTrue(s.messages.last().sourceRefs.isEmpty())
        assertEquals(s, api.decode(TutorSession.serializer(), api.send(HttpMethod.Get, tutorPath, token)))
        // File-backed durability, without touching game packages or inventing an in-memory-only session.
        assertEquals(s, com.anything2game.server.store.FileStore(env.dir.toPath()).getTutor(s.sessionId)?.session)
        api.send(HttpMethod.Delete, "/v1/documents/${doc.documentId}", token)
        assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, tutorPath, token).status)
        assertNull(env.store.getTutor(s.sessionId))
    } }

    @Test fun `cancelled request cannot later publish a turn`() = TestEnv().use { env -> env.run {
        val api = api(); val token = api.install().second
        val doc = api.document(token, pagesOf(Examples.retrieval))
        val create = api.send(HttpMethod.Post, "/v1/documents/${doc.documentId}/tutor", token, """{"clientRequestId":"start"}""")
        var s = api.decode(TutorSession.serializer(), create)
        val base = "/v1/tutors/${s.sessionId}"
        val answers = s.questions.associate { it.id to UNKNOWN_ANSWER }
        s = api.decode(TutorSession.serializer(), api.send(HttpMethod.Post, "$base/assessment", token,
            ContractJson.json.encodeToString(AssessTutorRequest.serializer(), AssessTutorRequest(0, answers, "answers"))))
        val rev = s.revision
        val cancel = api.send(HttpMethod.Post, "$base/cancel", token, """{"clientRequestId":"delayed"}""")
        assertEquals(HttpStatusCode.OK, cancel.status)
        val late = api.send(HttpMethod.Post, "$base/turns", token,
            ContractJson.json.encodeToString(TutorTurnRequest.serializer(), TutorTurnRequest(rev, TutorIntent.START, clientRequestId = "delayed")))
        assertEquals(HttpStatusCode.Conflict, late.status)
        assertTrue(late.bodyAsText().contains("TUTOR_CANCELLED"))
        assertTrue(env.store.getTutor(s.sessionId)!!.session.messages.isEmpty())
    } }
}
