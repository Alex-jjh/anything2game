package com.anything2game.contract.tutor

import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceRef
import com.anything2game.contract.LearningPackage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Draft integration contract, introduced for the document tutor. Not a team-approved product schema. */
const val TUTOR_SCHEMA = "a2g.tutor/draft-v1"
const val UNKNOWN_ANSWER = "__unknown__"

/** What a reader may want out of a document. A concept is tagged with the facet it mostly teaches. */
@Serializable enum class Facet {
    @SerialName("background") BACKGROUND,
    @SerialName("method") METHOD,
    @SerialName("result") RESULT,
    @SerialName("limitation") LIMITATION,
}
const val MAX_FOCUS_NOTE = 200

@Serializable data class TutorOption(val id: String, val text: String)
@Serializable data class TutorQuestion(
    val id: String, val conceptId: String, val concept: String, val prompt: String,
    val options: List<TutorOption>, val sourceRefs: List<SourceRef>,
    /** Optional: which facet this concept belongs to. Without it the concept is never moved by a focus. */
    val facet: Facet? = null,
)
/** Private scoring/teaching material. Never included in the server's pre-assessment response. */
@Serializable data class TutorConcept(
    val question: TutorQuestion, val correctOptionId: String,
    val explanation: String, val simpler: String, val example: String,
)
@Serializable data class TutorBlueprint(val schema: String = TUTOR_SCHEMA, val concepts: List<TutorConcept>)

/**
 * Pre-generated tutor content for one course: the `tutor.json` entry of a content pack (ADR-017) and the
 * `tutor.*.json` source files PackTool picks up. Versions must match the course it ships with.
 */
@Serializable data class TutorScript(val packageId: String, val contentVersion: String, val documentVersion: String, val blueprint: TutorBlueprint) {
    fun validate(pkg: LearningPackage): TutorScript {
        require(packageId == pkg.packageId && contentVersion == pkg.contentVersion && documentVersion == pkg.documentVersion) { "Tutor script version mismatch" }
        TutorRules.validate(blueprint, pkg.sources)
        return this
    }
}
@Serializable enum class Understanding { DEMONSTRATED, NEEDS_CLARIFICATION, NOT_YET_ASSESSED }
@Serializable data class ConceptAssessment(val questionId: String, val conceptId: String, val evidence: Understanding)
@Serializable enum class TutorIntent { START, REPHRASE, EXAMPLE, CONTINUE, QUESTION }
@Serializable data class TutorMessage(val id: String, val role: String, val text: String, val sourceRefs: List<SourceRef> = emptyList())
@Serializable data class TutorSession(
    val schema: String = TUTOR_SCHEMA, val sessionId: String, val documentId: String, val documentVersion: String,
    val title: String, val language: String, val mode: String, val questions: List<TutorQuestion>, val sources: List<SourceChunk>,
    val revision: Int = 0, val answers: Map<String, String> = emptyMap(),
    val assessment: List<ConceptAssessment> = emptyList(), val messages: List<TutorMessage> = emptyList(),
    val conceptPosition: Int = 0,
    /** What the reader wants to get out of the document. Empty = let the explainer decide. */
    val focus: List<Facet> = emptyList(), val focusNote: String = "",
)
@Serializable data class CreateTutorRequest(val language: String = "zh", val clientRequestId: String)
@Serializable data class AssessTutorRequest(val expectedRevision: Int, val answers: Map<String, String>, val clientRequestId: String)
@Serializable data class TutorTurnRequest(val expectedRevision: Int, val intent: TutorIntent, val question: String = "", val clientRequestId: String)

/** Pure checks shared by the local fixed example and server. Correct answers for real documents stay on the server. */
object TutorRules {
    fun validate(blueprint: TutorBlueprint, sources: List<SourceChunk>) {
        require(blueprint.schema == TUTOR_SCHEMA) { "Unsupported tutor schema" }
        require(blueprint.concepts.size in 1..6) { "Expected 1 to 6 concepts" }
        val qs = blueprint.concepts.map { it.question }
        require(qs.map { it.id }.distinct().size == qs.size && qs.map { it.conceptId }.distinct().size == qs.size)
        blueprint.concepts.forEach { c ->
            val q = c.question
            require(listOf(q.id, q.conceptId, q.concept, q.prompt, c.explanation, c.simpler, c.example).all { it.isNotBlank() && it.length <= 1800 })
            require(q.options.size in 2..4 && q.options.map { it.id }.distinct().size == q.options.size)
            require(q.options.all { it.id.isNotBlank() && it.id != UNKNOWN_ANSWER && it.text.isNotBlank() && it.text.length <= 800 })
            require(q.options.any { it.id == c.correctOptionId })
            checkRefs(q.sourceRefs, sources)
        }
    }

    fun checkRefs(refs: List<SourceRef>, sources: List<SourceChunk>) {
        require(refs.isNotEmpty() && refs.size <= 6)
        refs.forEach { ref ->
            val text = sources.firstOrNull { it.chunkId == ref.chunkId }?.text ?: error("Missing source")
            require(ref.quote.isNotBlank() && ref.quote.length <= 3000 && normalize(ref.quote) in normalize(text)) { "Quote is not in source" }
        }
    }
    private fun normalize(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun assess(blueprint: TutorBlueprint, answers: Map<String, String>): List<ConceptAssessment> {
        require(answers.keys == blueprint.concepts.map { it.question.id }.toSet()) { "Answer every question" }
        return blueprint.concepts.map { c ->
            val a = answers.getValue(c.question.id)
            require(a == UNKNOWN_ANSWER || c.question.options.any { it.id == a }) { "Invalid option" }
            ConceptAssessment(c.question.id, c.question.conceptId, when (a) {
                UNKNOWN_ANSWER -> Understanding.NOT_YET_ASSESSED
                c.correctOptionId -> Understanding.DEMONSTRATED
                else -> Understanding.NEEDS_CLARIFICATION
            })
        }
    }

    /** Chosen facets first, everything else after (stable; nothing is dropped). No focus, or no facets on the questions: unchanged. */
    fun orderByFocus(questions: List<TutorQuestion>, focus: List<Facet>): List<TutorQuestion> = questions.sortedBy { focusRank(it, focus) }

    private fun focusRank(q: TutorQuestion, focus: List<Facet>) = if (focus.isEmpty() || q.facet in focus) 0 else 1

    /** The focus UI only makes sense when the content says which facet each concept is. */
    fun carriesFacets(questions: List<TutorQuestion>) = questions.any { it.facet != null }

    fun normalizeFocus(facets: Collection<Facet>): List<Facet> = facets.distinct().sortedBy { it.ordinal }

    fun checkFocus(focus: List<Facet>, note: String) {
        require(focus.distinct().size == focus.size) { "Duplicate facet" }
        require(note.length <= MAX_FOCUS_NOTE) { "Focus note too long" }
    }

    /**
     * Teaching order, in four stable groups: (chosen facet and not demonstrated), (not demonstrated),
     * (chosen facet and demonstrated), (the rest). With no focus chosen only "not demonstrated first" applies.
     */
    fun orderedQuestions(session: TutorSession): List<TutorQuestion> {
        val demonstrated = session.assessment.filter { it.evidence == Understanding.DEMONSTRATED }.map { it.questionId }.toSet()
        return session.questions.sortedBy { q ->
            val chosen = session.focus.isNotEmpty() && q.facet in session.focus
            val known = q.id in demonstrated
            when { chosen && !known -> 0; !known -> 1; chosen -> 2; else -> 3 }
        }
    }

    /** One scripted turn pulled apart: the concept's name, the personalised lead-in line, and the explanation itself. */
    data class ScriptedParts(val title: String, val leadIn: String, val body: String)

    /** The lead-in that opens a scripted turn, by what the reader's diagnostic answer showed. */
    fun leadIn(evidence: Understanding, zh: Boolean): String = if (zh) when (evidence) {
        Understanding.DEMONSTRATED -> "这题你答对了，我们把它和原文连起来："
        Understanding.NEEDS_CLARIFICATION -> "这题有个地方容易混，我们先把它分清："
        Understanding.NOT_YET_ASSESSED -> "你选了不知道，那就从这里开始："
    } else when (evidence) {
        Understanding.DEMONSTRATED -> "You got this one right. Here is how it fits the reading:"
        Understanding.NEEDS_CLARIFICATION -> "Your answer mixed something up, so let's clear that first:"
        Understanding.NOT_YET_ASSESSED -> "You weren't sure, so let's start here:"
    }

    private val knownLeadIns: Set<String> = listOf(false, true).flatMap { zh -> Understanding.entries.map { leadIn(it, zh) } }.toSet()

    /**
     * Reads a message built by [scriptedReply] ("concept name, lead-in, explanation", one per line).
     * Anything else (a server message, a reply to a typed question) returns null and is shown as it is.
     */
    fun splitScripted(text: String, conceptNames: Collection<String>): ScriptedParts? {
        val lines = text.split("\n", limit = 3)
        if (lines.size < 3) return null
        val (title, lead, body) = lines
        if (title !in conceptNames || lead !in knownLeadIns || body.isBlank()) return null
        return ScriptedParts(title, lead, body.trim())
    }

    /** Scripted fake mode only; explicitly does not interpret free text. */
    fun scriptedReply(session: TutorSession, blueprint: TutorBlueprint, intent: TutorIntent): TutorMessage {
        require(session.assessment.size == session.questions.size && session.assessment.isNotEmpty())
        val q = orderedQuestions(session)[session.conceptPosition]
        val c = blueprint.concepts.first { it.question.id == q.id }
        val zh = session.language == "zh"
        if (intent == TutorIntent.QUESTION) return TutorMessage("m${session.revision + 1}a", "tutor",
            if (zh) "这门课我只能按事先准备好的讲解来讲，没法回答新的问题。你的问题已记在记录里。"
            else "I can only walk through the prepared explanation here, so I can't answer new questions on this course. Your question is saved in the log.")
        val evidence = session.assessment.first { it.questionId == q.id }.evidence
        val body = when (intent) {
            TutorIntent.REPHRASE -> c.simpler
            TutorIntent.EXAMPLE -> c.example
            else -> c.explanation
        }
        return TutorMessage("m${session.revision + 1}a", "tutor", "${q.concept}\n${leadIn(evidence, zh)}\n$body", q.sourceRefs)
    }
}
