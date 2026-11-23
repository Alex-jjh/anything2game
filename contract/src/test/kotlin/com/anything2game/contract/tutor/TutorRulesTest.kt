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
    @Test fun `a scene's lines are said on Start and Continue, checked against the source, and skipped by Rephrase`() {
        val beats = listOf(
            TutorBeat("Recall is pulling it back out.", section = "1 Intro", sourceRefs = listOf(SourceRef("s1", "retrieving information"))),
            TutorBeat("So no rereading?", speaker = SPEAKER_PLAYER),
            TutorBeat("Quick one.", check = TutorCheck("Which is recall?", listOf(TutorOption("a", "Retrieve"), TutorOption("b", "Reread")), "a", "Yes.", "Rereading is not recall.")),
        )
        val scene = bp.copy(concepts = listOf(bp.concepts.single().copy(beats = beats)))
        TutorRules.validate(scene, source)
        val s = TutorSession(sessionId = "t", documentId = "d", documentVersion = "v", title = "T", language = "en", mode = "local-mock", questions = listOf(q), sources = source,
            assessment = listOf(ConceptAssessment("q1", "c1", Understanding.NOT_YET_ASSESSED)))
        val start = TutorRules.scriptedReply(s, scene, TutorIntent.START)
        assertEquals(beats, start.beats)
        assertEquals("Recall is pulling it back out.\nSo no rereading?\nQuick one.", TutorRules.splitScripted(start.text, listOf("Recall"))!!.body)
        assertTrue(TutorRules.scriptedReply(s, scene, TutorIntent.REPHRASE).let { it.beats.isEmpty() && it.text.endsWith("Simple") })
        // A line quoting what the source does not say, an unknown speaker, or a check without its answer is refused.
        for (bad in listOf(beats[0].copy(sourceRefs = listOf(SourceRef("s1", "not in the text"))), beats[1].copy(speaker = "narrator"), beats[2].copy(check = beats[2].check!!.copy(correctOptionId = "z"))))
            assertThrows(IllegalArgumentException::class.java) { TutorRules.validate(bp.copy(concepts = listOf(bp.concepts.single().copy(beats = listOf(bad)))), source) }
        // Content written before scenes existed still decodes.
        assertTrue(ContractJson.json.decodeFromString(TutorMessage.serializer(), "{\"id\":\"m\",\"role\":\"tutor\",\"text\":\"x\"}").beats.isEmpty())
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
        val free = TutorRules.scriptedReply(s.copy(language = "en"), bp.copy(concepts = bp.concepts + bp.concepts.single().copy(question = q2)), TutorIntent.QUESTION)
        assertTrue(free.sourceRefs.isEmpty())
        // A student-facing sentence: says what the explainer can do, not how the build is configured.
        assertTrue(free.text.contains("can't answer new questions"))
        assertTrue(free.text.contains("saved in the log"))
        assertFalse(Regex("(?i)mock|server model|configured").containsMatchIn(free.text))
        val zh = TutorRules.scriptedReply(s, bp.copy(concepts = bp.concepts + bp.concepts.single().copy(question = q2)), TutorIntent.QUESTION)
        assertTrue(zh.text.contains("没法回答新的问题") && zh.text.contains("记在记录里"))
        assertFalse(Regex("(?i)mock|模型|配置").containsMatchIn(zh.text))
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
        // Method is chosen but already demonstrated: it comes after everything not yet demonstrated.
        assertEquals(listOf("b", "r", "m"), TutorRules.orderedQuestions(session(qs, listOf(Facet.METHOD), assessment)).map { it.id })
    }

    @Test fun `focus order has four groups - chosen and open, open, chosen and known, rest`() {
        val qs = faceted("b" to Facet.BACKGROUND, "m" to Facet.METHOD, "r" to Facet.RESULT, "l" to Facet.LIMITATION, "x" to null, "y" to Facet.METHOD)
        fun ev(id: String, e: Understanding) = ConceptAssessment(id, "c-$id", e)
        val assessment = listOf(
            ev("b", Understanding.DEMONSTRATED), ev("m", Understanding.NEEDS_CLARIFICATION), ev("r", Understanding.DEMONSTRATED),
            ev("l", Understanding.NOT_YET_ASSESSED), ev("x", Understanding.DEMONSTRATED), ev("y", Understanding.DEMONSTRATED),
        )
        // Focus = method + result. m: chosen+open (0). l: open (1). r, y: chosen+known (2, script order r then y... y is METHOD, r is RESULT). b, x: rest (3).
        assertEquals(listOf("m", "l", "r", "y", "b", "x"), TutorRules.orderedQuestions(session(qs, listOf(Facet.METHOD, Facet.RESULT), assessment)).map { it.id })
        // Stable inside a group: two chosen open concepts keep the script's order.
        val open = listOf(ev("b", Understanding.NOT_YET_ASSESSED), ev("m", Understanding.NOT_YET_ASSESSED), ev("r", Understanding.NOT_YET_ASSESSED), ev("l", Understanding.NOT_YET_ASSESSED), ev("x", Understanding.NOT_YET_ASSESSED), ev("y", Understanding.NOT_YET_ASSESSED))
        assertEquals(listOf("m", "r", "y", "b", "l", "x"), TutorRules.orderedQuestions(session(qs, listOf(Facet.METHOD, Facet.RESULT), open)).map { it.id })
        // No focus: today's order (not demonstrated first, then the rest), unaffected by facets.
        assertEquals(listOf("m", "l", "b", "r", "x", "y"), TutorRules.orderedQuestions(session(qs, emptyList(), assessment)).map { it.id })
        // A focus on facets nothing carries changes nothing.
        assertEquals(listOf("m", "l", "b", "r", "x", "y"), TutorRules.orderedQuestions(session(faceted("b" to null, "m" to null, "r" to null, "l" to null, "x" to null, "y" to null), listOf(Facet.RESULT), assessment)).map { it.id })
    }

    @Test fun `a scripted turn splits into title, lead-in and body, anything else stays whole`() {
        val q2 = q.copy(id = "q2", conceptId = "c2", concept = "Spacing")
        val s = session(listOf(q, q2), assessment = listOf(ConceptAssessment("q1", "c1", Understanding.NOT_YET_ASSESSED), ConceptAssessment("q2", "c2", Understanding.NEEDS_CLARIFICATION)))
        val b = bp.copy(concepts = bp.concepts + bp.concepts.single().copy(question = q2))
        val names = s.questions.map { it.concept }
        for (zh in listOf(false, true)) for (intent in listOf(TutorIntent.START, TutorIntent.REPHRASE, TutorIntent.EXAMPLE, TutorIntent.CONTINUE)) {
            val msg = TutorRules.scriptedReply(s.copy(language = if (zh) "zh" else "en"), b, intent)
            val parts = TutorRules.splitScripted(msg.text, names)!!
            assertEquals(TutorRules.orderedQuestions(s)[0].concept, parts.title)
            assertEquals(TutorRules.leadIn(Understanding.NOT_YET_ASSESSED, zh), parts.leadIn)
            assertEquals(when (intent) { TutorIntent.REPHRASE -> "Simple"; TutorIntent.EXAMPLE -> "Example"; else -> "Explanation" }, parts.body)
            // The three parts together are the message: nothing is lost.
            assertEquals(msg.text, "${parts.title}\n${parts.leadIn}\n${parts.body}")
        }
        // A reply to a typed question, a server message and a lookalike are shown as they are.
        assertNull(TutorRules.splitScripted(TutorRules.scriptedReply(s, b, TutorIntent.QUESTION).text, names))
        assertNull(TutorRules.splitScripted("Recall\nSome other line\nBody", names))
        assertNull(TutorRules.splitScripted("Not a concept\n${TutorRules.leadIn(Understanding.DEMONSTRATED, false)}\nBody", names))
        assertNull(TutorRules.splitScripted("Recall\n${TutorRules.leadIn(Understanding.DEMONSTRATED, false)}", names))
        assertNull(TutorRules.splitScripted("Recall explained in one paragraph.", names))
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
