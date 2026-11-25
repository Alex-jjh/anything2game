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
/**
 * One line of a scripted walk-through (the paper2gal style: many short lines, not one paragraph): who says it, where in
 * the document it is, and the passage it rests on. Optional everywhere; content without beats is shown as before.
 */
@Serializable data class TutorBeat(
    val text: String,
    /** [SPEAKER_EXPLAINER], or [SPEAKER_PLAYER]: the reader's own character voicing the question a reader would have. */
    val speaker: String = SPEAKER_EXPLAINER,
    /** Where in the document this line is, as the document names it ("3.2 Attention", "Table 3"); shown with the progress. */
    val section: String? = null,
    val sourceRefs: List<SourceRef> = emptyList(),
    /** A quick check in the middle of the explanation. Scripted courses only (the answer travels with it). */
    val check: TutorCheck? = null,
)
@Serializable data class TutorCheck(val prompt: String, val options: List<TutorOption>, val correctOptionId: String, val right: String, val wrong: String)
const val SPEAKER_EXPLAINER = "explainer"
const val SPEAKER_PLAYER = "player"

/** Private scoring/teaching material. Never included in the server's pre-assessment response. */
@Serializable data class TutorConcept(
    val question: TutorQuestion, val correctOptionId: String,
    val explanation: String, val simpler: String, val example: String,
    /** The explanation as a scene, line by line. When present it is what the explainer says; [explanation] stays the summary. */
    val beats: List<TutorBeat> = emptyList(),
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
@Serializable enum class Understanding {
    DEMONSTRATED, NEEDS_CLARIFICATION, NOT_YET_ASSESSED,
    /** The reader was never asked: a lesson opened from a route step skips the diagnostic. */
    NOT_ASKED,
}
@Serializable data class ConceptAssessment(val questionId: String, val conceptId: String, val evidence: Understanding)
@Serializable enum class TutorIntent {
    START, REPHRASE, EXAMPLE, CONTINUE, QUESTION,
    /** Explain a concept that was already introduced again, at `targetPosition`: going back, or starting the walk over. */
    REVISIT,
}
@Serializable data class TutorMessage(
    val id: String, val role: String, val text: String, val sourceRefs: List<SourceRef> = emptyList(),
    /** The same reply as scene lines, when the content has them; [text] stays the full reply (Log, older clients). */
    val beats: List<TutorBeat> = emptyList(),
)
/**
 * How a lesson was made. The wire strings ("local-mock", "mock", "model") are unchanged; they are read into this enum once, when the
 * session is decoded, so nothing downstream compares strings. A string outside the three is refused at that boundary.
 */
@Serializable enum class TutorMode {
    /** A script bundled with the app, played on the phone: it never reaches the server. */
    @SerialName("local-mock") LOCAL_MOCK,
    /** Made on the server by the test generator, with no model behind it. */
    @SerialName("mock") MOCK,
    /** Written by a model on the server. */
    @SerialName("model") MODEL;

    /** The scripted walk-through on the phone, as opposed to a lesson the server made. */
    val scripted get() = this == LOCAL_MOCK
}

@Serializable data class TutorSession(
    val schema: String = TUTOR_SCHEMA, val sessionId: String, val documentId: String, val documentVersion: String,
    val title: String, val language: String, val mode: TutorMode, val questions: List<TutorQuestion>, val sources: List<SourceChunk>,
    val revision: Int = 0, val answers: Map<String, String> = emptyMap(),
    val assessment: List<ConceptAssessment> = emptyList(), val messages: List<TutorMessage> = emptyList(),
    val conceptPosition: Int = 0,
    /** How many concepts the walk has introduced (its furthest point). 0 in sessions saved before revisiting existed. */
    val conceptsIntroduced: Int = 0,
    /** What the reader wants to get out of the document. Empty = let the explainer decide. */
    val focus: List<Facet> = emptyList(), val focusNote: String = "",
)
@Serializable data class CreateTutorRequest(val language: String = "zh", val clientRequestId: String)
@Serializable data class AssessTutorRequest(val expectedRevision: Int, val answers: Map<String, String>, val clientRequestId: String)
@Serializable data class TutorTurnRequest(
    val expectedRevision: Int, val intent: TutorIntent, val question: String = "", val clientRequestId: String,
    /** REVISIT only: which already-introduced concept to explain again (0-based, at most the furthest reached). */
    val targetPosition: Int? = null,
)

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
            checkBeats(c.beats, sources)
        }
    }

    fun checkBeats(beats: List<TutorBeat>, sources: List<SourceChunk>) {
        require(beats.size <= 40) { "Too many lines" }
        beats.forEach { b ->
            require(b.text.isNotBlank() && b.text.length <= 400 && b.speaker in setOf(SPEAKER_EXPLAINER, SPEAKER_PLAYER))
            require(b.section == null || b.section.isNotBlank() && b.section.length <= 60)
            if (b.sourceRefs.isNotEmpty()) checkRefs(b.sourceRefs, sources)
            b.check?.let { k ->
                require(k.prompt.isNotBlank() && k.right.isNotBlank() && k.wrong.isNotBlank())
                require(k.options.size in 2..4 && k.options.map { it.id }.distinct().size == k.options.size && k.options.all { it.text.isNotBlank() })
                require(k.options.any { it.id == k.correctOptionId })
            }
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

    /** The assessment of a session that skipped the diagnostic: nobody was asked, so nothing is claimed. */
    fun unasked(questions: List<TutorQuestion>): List<ConceptAssessment> = questions.map { ConceptAssessment(it.id, it.conceptId, Understanding.NOT_ASKED) }

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

    /** The furthest concept the walk has reached, for a session saved before [TutorSession.conceptsIntroduced] existed. */
    fun introduced(session: TutorSession): Int = maxOf(session.conceptsIntroduced, session.conceptPosition + 1).coerceAtMost(session.questions.size)

    /**
     * Where a turn lands. CONTINUE moves on, REVISIT goes to an already-introduced concept, everything else stays put.
     * Throws when the move is not allowed, so a request cannot skip a concept or revisit one that was never introduced.
     */
    fun positionAfter(session: TutorSession, intent: TutorIntent, targetPosition: Int? = null): Int {
        require(intent == TutorIntent.REVISIT || targetPosition == null) { "Only REVISIT takes a target" }
        val position = when (intent) {
            TutorIntent.CONTINUE -> session.conceptPosition + 1
            TutorIntent.REVISIT -> requireNotNull(targetPosition) { "REVISIT needs a target" }
            else -> session.conceptPosition
        }
        require(position in session.questions.indices) { "No such concept" }
        if (intent == TutorIntent.REVISIT) require(position < introduced(session)) { "That concept has not been introduced yet" }
        return position
    }

    /** One scripted turn pulled apart: the concept's name, the personalised lead-in line, and the explanation itself. */
    data class ScriptedParts(val title: String, val leadIn: String, val body: String)

    /** The lead-in that opens a scripted turn, by what the reader's diagnostic answer showed. */
    fun leadIn(evidence: Understanding, zh: Boolean): String = if (zh) when (evidence) {
        Understanding.DEMONSTRATED -> "这题你答对了，我们把它和原文连起来："
        Understanding.NEEDS_CLARIFICATION -> "这题有个地方容易混，我们先把它分清："
        Understanding.NOT_YET_ASSESSED -> "你选了不知道，那就从这里开始："
        Understanding.NOT_ASKED -> "我们来看这个概念："
    } else when (evidence) {
        Understanding.DEMONSTRATED -> "You got this one right. Here is how it fits the reading:"
        Understanding.NEEDS_CLARIFICATION -> "Your answer mixed something up, so let's clear that first:"
        Understanding.NOT_YET_ASSESSED -> "You weren't sure, so let's start here:"
        Understanding.NOT_ASKED -> "Let's look at this one:"
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
            if (zh) "这份讲解是提前写好的，没法回答新的问题。你的问题已记在对话记录里。"
            else "This explanation was written in advance, so I can't answer new questions here. Your question is saved in History.")
        val evidence = session.assessment.first { it.questionId == q.id }.evidence
        val body = when (intent) {
            TutorIntent.REPHRASE -> c.simpler
            TutorIntent.EXAMPLE -> c.example
            else -> c.explanation
        }

        // A course written as a scene says its lines; Rephrase and Example stay one short reply each.
        val beats = if (intent in setOf(TutorIntent.START, TutorIntent.CONTINUE, TutorIntent.REVISIT)) c.beats else emptyList()
        val said = if (beats.isEmpty()) body else beats.joinToString("\n") { it.text }
        return TutorMessage("m${session.revision + 1}a", "tutor", "${q.concept}\n${leadIn(evidence, zh)}\n$said", q.sourceRefs, beats)
    }
}
