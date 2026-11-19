package com.anything2game.contract.tutor

import com.anything2game.contract.*
import org.junit.Assert.*
import org.junit.Test

class TutorRulesTest {
    private val source = listOf(SourceChunk("s1", 0, "1", "Recall means retrieving information from memory."))
    private val q = TutorQuestion("q1", "c1", "Recall", "What is recall?", listOf(TutorOption("a", "Memory"), TutorOption("b", "Reread")), listOf(SourceRef("s1", "retrieving information from memory")))
    private val bp = TutorBlueprint(concepts = listOf(TutorConcept(q, "a", "Explanation", "Simple", "Example")))
    @Test fun `unknown wrong and correct are distinct evidence`() {
        assertEquals(Understanding.NOT_YET_ASSESSED, TutorRules.assess(bp, mapOf("q1" to UNKNOWN_ANSWER)).single().evidence)
        assertEquals(Understanding.NEEDS_CLARIFICATION, TutorRules.assess(bp, mapOf("q1" to "b")).single().evidence)
        assertEquals(Understanding.DEMONSTRATED, TutorRules.assess(bp, mapOf("q1" to "a")).single().evidence)
    }
    @Test fun `incomplete and foreign answers cannot be assessed`() {
        assertThrows(IllegalArgumentException::class.java) { TutorRules.assess(bp, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.assess(bp, mapOf("q1" to "other")) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.assess(bp, mapOf("q1" to "a", "q2" to "a")) }
    }
    @Test fun `source and answer relationships must be valid`() {
        TutorRules.validate(bp, source)
        assertThrows(IllegalArgumentException::class.java) { TutorRules.validate(bp.copy(concepts = listOf(bp.concepts.single().copy(correctOptionId = "x"))), source) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.validate(bp, source.map { it.copy(text = "unrelated") }) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.validate(bp.copy(schema = "future"), source) }
    }
    @Test fun `missing fields and broken JSON fail safely`() {
        for (raw in listOf("{", "{}", "{\"concepts\":[{}]}")) {
            assertTrue(runCatching { ContractJson.json.decodeFromString(TutorBlueprint.serializer(), raw).also { TutorRules.validate(it, source) } }.isFailure)
        }
    }
    @Test fun `teaching prioritizes uncertain concepts without changing formal mastery`() {
        val q2 = q.copy(id = "q2", conceptId = "c2")
        val s = TutorSession(sessionId = "t", documentId = "d", documentVersion = "v", title = "T", language = "zh", mode = "mock", questions = listOf(q, q2), sources = source,
            assessment = listOf(ConceptAssessment("q1", "c1", Understanding.DEMONSTRATED), ConceptAssessment("q2", "c2", Understanding.NOT_YET_ASSESSED)))
        assertEquals(listOf("q2", "q1"), TutorRules.orderedQuestions(s).map { it.id })
        val free = TutorRules.scriptedReply(s, bp.copy(concepts = bp.concepts + bp.concepts.single().copy(question = q2)), TutorIntent.QUESTION)
        assertTrue(free.sourceRefs.isEmpty())
        assertTrue(free.text.contains("mock"))
    }
}
