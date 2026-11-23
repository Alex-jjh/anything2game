package com.anything2game.app.data.tutor

import android.util.AtomicFile
import com.anything2game.contract.ContractJson
import com.anything2game.contract.tutor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/** Local display metadata, bound to a reply so a refreshed server message cannot inherit the wrong label. */
@Serializable data class TutorReplyPresentation(val messageId: String, val intent: TutorIntent)

@Serializable data class TutorCache(
    val session: TutorSession, val draftAnswers: Map<String, String> = emptyMap(), val questionIndex: Int = 0,
    /** The reader has been asked what they want out of the reading (even if they let the explainer decide). */
    val focusChosen: Boolean = false,
    val lastReply: TutorReplyPresentation? = null,
) {
    // Older caches have no presentation metadata; show no specific mode rather than guessing from prose.
    val replyIntent: TutorIntent?
        get() = lastReply?.takeIf { it.messageId == session.messages.lastOrNull { m -> m.role == "tutor" }?.id }?.intent

    /** Records the reader's focus and moves the chosen facets' questions first. Only before the diagnostic is assessed. */
    fun withFocus(facets: Collection<Facet>, note: String): TutorCache {
        require(session.assessment.isEmpty()) { "Focus is chosen before the diagnostic" }
        val chosen = TutorRules.normalizeFocus(facets)
        val text = note.trim()
        TutorRules.checkFocus(chosen, text)
        return copy(session = session.copy(focus = chosen, focusNote = text, questions = TutorRules.orderByFocus(session.questions, chosen)), questionIndex = 0, focusChosen = true)
    }
    fun validate(): TutorCache {
        val s = session
        require(s.schema == TUTOR_SCHEMA && s.questions.isNotEmpty() && s.questions.size <= 6)
        require(s.revision >= 0 && s.mode in setOf("mock", "model", "local-mock"))
        require(s.questions.map { it.id }.distinct().size == s.questions.size)
        TutorRules.checkFocus(s.focus, s.focusNote)
        require(questionIndex in s.questions.indices && s.conceptPosition in s.questions.indices)
        s.questions.forEach { q ->
            require(q.id.isNotBlank() && q.conceptId.isNotBlank() && q.prompt.isNotBlank())
            require(q.options.size in 2..4 && q.options.map { it.id }.distinct().size == q.options.size)
            require(q.options.all { it.id.isNotBlank() && it.id != UNKNOWN_ANSWER && it.text.isNotBlank() })
            TutorRules.checkRefs(q.sourceRefs, s.sources)
        }
        for ((id, option) in draftAnswers + s.answers) {
            val q = s.questions.firstOrNull { it.id == id } ?: error("Unknown saved question")
            require(option == UNKNOWN_ANSWER || q.options.any { it.id == option })
        }
        require(s.assessment.isEmpty() || s.assessment.map { it.questionId }.toSet() == s.questions.map { it.id }.toSet())
        require(s.assessment.map { it.questionId }.distinct().size == s.assessment.size)
        s.assessment.forEach { a -> require(s.questions.any { it.id == a.questionId && it.conceptId == a.conceptId }) }
        require(if (s.assessment.isEmpty()) s.answers.isEmpty() else s.answers.keys == s.questions.map { it.id }.toSet())
        require(s.messages.size <= 80 && s.messages.map { it.id }.distinct().size == s.messages.size)
        require(s.messages.all { it.role in setOf("user", "tutor") && it.text.isNotBlank() && it.text.length <= 4000 })
        require(s.messages.isEmpty() || s.assessment.isNotEmpty())
        s.messages.filter { it.sourceRefs.isNotEmpty() }.forEach { TutorRules.checkRefs(it.sourceRefs, s.sources) }
        s.messages.forEach { TutorRules.checkBeats(it.beats, s.sources) }
        return this
    }
}

/** A separate namespace; never reads, migrates, clears or rewrites game/mastery/old self-report data. */
class TutorCacheStore(private val directory: File) {
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
    suspend fun read(key: String): TutorCache? = withContext(Dispatchers.IO) {
        val target = file(key)
        if (!target.exists() && !File(target.path + ".bak").exists()) null
        else AtomicFile(target).openRead().bufferedReader().use { ContractJson.json.decodeFromString(TutorCache.serializer(), it.readText()).validate() }
    }
    suspend fun write(cache: TutorCache) = withContext(Dispatchers.IO) {
        cache.validate()
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file(cache.session.sessionId))
        val output = atomic.startWrite()
        try {
            output.write(ContractJson.json.encodeToString(TutorCache.serializer(), cache).toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    suspend fun list(): List<TutorSession> = withContext(Dispatchers.IO) {
        directory.listFiles { f -> f.extension == "json" }.orEmpty().map { f ->
            AtomicFile(f).openRead().bufferedReader().use { ContractJson.json.decodeFromString(TutorCache.serializer(), it.readText()).validate().session }
        }.filter { it.mode != "local-mock" }.sortedBy { it.title }
    }
}
