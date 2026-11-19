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

    private fun faceted(vararg f: Pair<String, Facet?>) = f.map { (id, facet) -> q.copy(id = id, conceptId = "c-$id", facet = facet) }
    private fun session(qs: List<TutorQuestion>, focus: List<Facet> = emptyList(), assessment: List<ConceptAssessment> = emptyList()) =
        TutorSession(sessionId = "t", documentId = "d", documentVersion = "v", title = "T", language = "en", mode = "mock", questions = qs, sources = source, focus = focus, assessment = assessment)

    @Test fun `chosen facets come first and nothing is dropped`() {
        val qs = faceted("b" to Facet.BACKGROUND, "m" to Facet.METHOD, "r" to Facet.RESULT, "l" to Facet.LIMITATION, "x" to null)
        assertEquals(listOf("b", "m", "r", "l", "x"), TutorRules.orderByFocus(qs, emptyList()).map { it.id })
        assertEquals(listOf("m", "r", "b", "l", "x"), TutorRules.orderByFocus(qs, listOf(Facet.METHOD, Facet.RESULT)).map { it.id })
        assertEquals(listOf("l", "b", "m", "r", "x"), TutorRules.orderByFocus(qs, listOf(Facet.LIMITATION)).map { it.id })
        assertEquals(qs.map { it.id }.toSet(), TutorRules.orderByFocus(qs, Facet.entries).map { it.id }.toSet())
    }

    @Test fun `focus without facets on the questions changes nothing`() {
        val qs = faceted("a" to null, "b" to null)
        assertFalse(TutorRules.carriesFacets(qs))
        assertEquals(listOf("a", "b"), TutorRules.orderByFocus(qs, listOf(Facet.RESULT)).map { it.id })
        assertTrue(TutorRules.carriesFacets(faceted("a" to null, "b" to Facet.METHOD)))
    }

    @Test fun `explanation order puts the focus first, then uncertain concepts`() {
        val qs = faceted("b" to Facet.BACKGROUND, "m" to Facet.METHOD, "r" to Facet.RESULT)
        val assessment = listOf(ConceptAssessment("b", "c-b", Understanding.NEEDS_CLARIFICATION), ConceptAssessment("m", "c-m", Understanding.DEMONSTRATED), ConceptAssessment("r", "c-r", Understanding.NOT_YET_ASSESSED))
        assertEquals(listOf("b", "r", "m"), TutorRules.orderedQuestions(session(qs, assessment = assessment)).map { it.id })
        // Method is chosen: it goes first even though the reader already got it right; the rest keep the uncertain-first order.
        assertEquals(listOf("m", "b", "r"), TutorRules.orderedQuestions(session(qs, listOf(Facet.METHOD), assessment)).map { it.id })
    }

    @Test fun `focus note and duplicates are checked`() {
        TutorRules.checkFocus(listOf(Facet.METHOD), "x".repeat(MAX_FOCUS_NOTE))
        assertThrows(IllegalArgumentException::class.java) { TutorRules.checkFocus(emptyList(), "x".repeat(MAX_FOCUS_NOTE + 1)) }
        assertThrows(IllegalArgumentException::class.java) { TutorRules.checkFocus(listOf(Facet.METHOD, Facet.METHOD), "") }
        assertEquals(listOf(Facet.METHOD, Facet.RESULT), TutorRules.normalizeFocus(listOf(Facet.RESULT, Facet.METHOD, Facet.RESULT)))
    }

    @Test fun `old JSON without facet or focus still decodes, and facets use lower-case names`() {
        val oldQuestion = ContractJson.json.encodeToString(TutorQuestion.serializer(), q).replace(Regex(",?\"facet\":[^,}]*"), "")
        assertNull(ContractJson.json.decodeFromString(TutorQuestion.serializer(), oldQuestion).facet)
        val oldSession = ContractJson.json.encodeToString(TutorSession.serializer(), session(listOf(q))).replace(Regex(",?\"focus\":\\[\\]"), "").replace(Regex(",?\"focusNote\":\"\""), "")
        assertFalse(oldSession.contains("focus"))
        val back = ContractJson.json.decodeFromString(TutorSession.serializer(), oldSession)
        assertEquals(emptyList<Facet>(), back.focus); assertEquals("", back.focusNote)
        assertEquals(Facet.RESULT, ContractJson.json.decodeFromString(TutorQuestion.serializer(), ContractJson.json.encodeToString(TutorQuestion.serializer(), q.copy(facet = Facet.RESULT))).facet)
        assertTrue(ContractJson.json.encodeToString(TutorQuestion.serializer(), q.copy(facet = Facet.LIMITATION)).contains("\"facet\":\"limitation\""))
    }
}
