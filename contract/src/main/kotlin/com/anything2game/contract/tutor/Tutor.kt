package com.anything2game.contract.tutor

import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceRef
import kotlinx.serialization.Serializable

/** Draft integration contract, introduced for the document tutor. Not a team-approved product schema. */
const val TUTOR_SCHEMA = "a2g.tutor/draft-v1"
const val UNKNOWN_ANSWER = "__unknown__"

@Serializable data class TutorOption(val id: String, val text: String)
@Serializable data class TutorQuestion(
    val id: String, val conceptId: String, val concept: String, val prompt: String,
    val options: List<TutorOption>, val sourceRefs: List<SourceRef>,
)
/** Private scoring/teaching material. Never included in the server's pre-assessment response. */
@Serializable data class TutorConcept(
    val question: TutorQuestion, val correctOptionId: String,
    val explanation: String, val simpler: String, val example: String,
)
@Serializable data class TutorBlueprint(val schema: String = TUTOR_SCHEMA, val concepts: List<TutorConcept>)
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

    fun orderedQuestions(session: TutorSession): List<TutorQuestion> = session.questions.sortedBy { q ->
        if (session.assessment.firstOrNull { it.questionId == q.id }?.evidence == Understanding.DEMONSTRATED) 1 else 0
    }

    /** Scripted fake mode only; explicitly does not interpret free text. */
    fun scriptedReply(session: TutorSession, blueprint: TutorBlueprint, intent: TutorIntent): TutorMessage {
        require(session.assessment.size == session.questions.size && session.assessment.isNotEmpty())
        val q = orderedQuestions(session)[session.conceptPosition]
        val c = blueprint.concepts.first { it.question.id == q.id }
        val zh = session.language == "zh"
        if (intent == TutorIntent.QUESTION) return TutorMessage("m${session.revision + 1}a", "tutor",
            if (zh) "已保留你的追问。本机使用 mock 教师，不能理解自由提问。请用下方按钮查看预设讲解；真实对话需在服务器配置模型。" else "Your question is saved. This mock tutor cannot interpret free-text questions. Use the teaching buttons; real conversation requires a configured server model.")
        val evidence = session.assessment.first { it.questionId == q.id }.evidence
        val prefix = if (zh) when (evidence) {
            Understanding.DEMONSTRATED -> "你在这道基础题上答对了。我们简要连接到文章："
            Understanding.NEEDS_CLARIFICATION -> "这道题提示我们先澄清一个区别："
            Understanding.NOT_YET_ASSESSED -> "你选了不知道，我们从这个概念开始："
        } else when (evidence) {
            Understanding.DEMONSTRATED -> "You answered this item correctly. Here is the connection to the document:"
            Understanding.NEEDS_CLARIFICATION -> "Let us clarify this distinction first:"
            Understanding.NOT_YET_ASSESSED -> "You chose not sure. Let us start with this concept:"
        }
        val body = when (intent) {
            TutorIntent.REPHRASE -> c.simpler
            TutorIntent.EXAMPLE -> c.example
            else -> c.explanation
        }
        return TutorMessage("m${session.revision + 1}a", "tutor", "${q.concept}\n$prefix\n$body", q.sourceRefs)
    }
}
