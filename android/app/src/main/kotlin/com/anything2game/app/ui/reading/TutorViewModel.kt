package com.anything2game.app.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.data.tutor.*
import com.anything2game.contract.tutor.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class TutorUiState(val cache: TutorCache? = null, val busy: Boolean = true, val problem: String? = null, val offline: Boolean = false, val unsupported: Boolean = false)

class TutorViewModel(private val repository: TutorRepository, private val id: String) : ViewModel() {
    private val mutable = MutableStateFlow(TutorUiState())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var job: Job? = null
    private var pending: Pair<String, suspend (TutorCache, String) -> TutorCache>? = null
    init { reload() }

    fun reload() {
        val epoch = ++generation
        job?.cancel(); pending = null
        mutable.value = mutable.value.copy(busy = true, problem = null)
        job = viewModelScope.launch {
            try {
                val result = repository.open(id)
                if (epoch == generation) mutable.value = TutorUiState(result.cache, false, offline = result.offline)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) mutable.value = mutable.value.copy(busy = false, problem = code(e), unsupported = e is TutorUnavailable) }
        }
    }
    /** Picking an answer is the whole step: the next question follows, and the last answer opens the starting-point screen. */
    fun choose(option: String) {
        val c = mutable.value.cache ?: return
        val q = c.session.questions[c.questionIndex]
        if (c.session.assessment.isNotEmpty() || (option != UNKNOWN_ANSWER && q.options.none { it.id == option })) return
        val last = c.questionIndex == c.session.questions.lastIndex
        execute { old, requestId ->
            val answered = old.copy(draftAnswers = old.draftAnswers + (q.id to option))
            if (!last) answered.copy(questionIndex = answered.questionIndex + 1)
            else answered.copy(session = repository.assess(answered, requestId))
        }
    }
    /** What the reader wants out of this reading; empty facets and a blank note = let the explainer decide. */
    fun setFocus(facets: List<Facet>, note: String) {
        val c = mutable.value.cache ?: return
        if (c.session.assessment.isNotEmpty()) return
        execute { old, _ -> repository.setFocus(old, facets, note) }
    }
    fun previous() = execute { c, _ -> c.copy(questionIndex = (c.questionIndex - 1).coerceAtLeast(0)) }
    fun next() {
        val c = mutable.value.cache ?: return
        if (c.session.questions[c.questionIndex].id !in c.draftAnswers) return
        if (c.questionIndex < c.session.questions.lastIndex) execute { old, _ -> old.copy(questionIndex = old.questionIndex + 1) }
        else execute { old, requestId -> old.copy(session = repository.assess(old, requestId)) }
    }
    fun turn(intent: TutorIntent, question: String = "", targetPosition: Int? = null) {
        if (intent == TutorIntent.QUESTION && (question.isBlank() || question.length > 1000)) return
        // A move the rules refuse (a concept not introduced yet) is not a request to send.
        val session0 = mutable.value.cache?.session ?: return
        if (runCatching { TutorRules.positionAfter(session0, intent, targetPosition) }.isFailure) return
        execute { c, requestId -> turned(c, intent, question.trim(), requestId, targetPosition) }
    }
    private suspend fun turned(c: TutorCache, intent: TutorIntent, question: String, requestId: String, targetPosition: Int?): TutorCache {
        val session = repository.turn(c, intent, question, requestId, targetPosition)
        val reply = session.messages.lastOrNull { it.role == "tutor" }
        val presentation = reply?.takeIf { it.id != c.session.messages.lastOrNull { m -> m.role == "tutor" }?.id }
            ?.let { TutorReplyPresentation(it.id, intent) } ?: c.lastReply
        return c.copy(session = session, lastReply = presentation)
    }

    /**
     * A route step names a concept and the reader goes straight to it: no starting assessment and no focus question (nobody was
     * asked, so nothing is claimed about what they know). Concepts before it stay reachable in the concept list. A walk that is
     * already at the concept is left alone; one that has been there goes back to it.
     * A server session cannot jump forward past what it has introduced, so it opens where its own walk is.
     */
    fun openAt(conceptId: String) {
        val c = mutable.value.cache ?: return
        if (c.session.questions.none { it.conceptId == conceptId }) return
        val s = c.session
        // Already teaching that very concept: nothing to ask, send or save.
        if (s.assessment.isNotEmpty() && s.messages.any { it.role == "tutor" } && TutorRules.orderedQuestions(s)[s.conceptPosition].conceptId == conceptId) return
        execute { old, requestId ->
            var cache = old
            val scripted = cache.session.mode == TutorMode.LOCAL_MOCK
            if (cache.session.assessment.isEmpty()) {
                val unknown = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER })
                val assessed = repository.assess(unknown, "$requestId-open")
                cache = unknown.copy(session = if (scripted) assessed.copy(assessment = TutorRules.unasked(assessed.questions)) else assessed)
            }
            val session = cache.session
            val target = TutorRules.orderedQuestions(session).indexOfFirst { it.conceptId == conceptId }
            val teaching = session.messages.any { it.role == "tutor" }
            when {
                teaching && target == session.conceptPosition -> cache
                teaching && target < TutorRules.introduced(session) -> turned(cache, TutorIntent.REVISIT, "", requestId, target)
                scripted -> turned(cache.copy(session = session.copy(conceptPosition = target,
                    conceptsIntroduced = maxOf(TutorRules.introduced(session), target + 1))), TutorIntent.START, "", requestId, null)
                teaching -> cache
                else -> turned(cache, TutorIntent.START, "", requestId, null)
            }
        }
    }
    fun retry() {
        val p = pending
        if (p == null || mutable.value.problem in setOf("TUTOR_STALE", "TUTOR_CANCELLED", "TUTOR_ALREADY_ASSESSED")) reload()
        else run(p)
    }
    private fun execute(change: suspend (TutorCache, String) -> TutorCache) {
        if (mutable.value.busy || mutable.value.problem != null) return
        pending = UUID.randomUUID().toString() to change
        run(pending!!)
    }
    private fun run(operation: Pair<String, suspend (TutorCache, String) -> TutorCache>) {
        val cache = mutable.value.cache ?: return
        val epoch = ++generation
        mutable.value = mutable.value.copy(busy = true, problem = null)
        job = viewModelScope.launch {
            try {
                val next = operation.second(cache, operation.first).validate()
                if (epoch != generation) return@launch
                repository.save(next)
                if (epoch == generation) { mutable.value = TutorUiState(next, false); pending = null }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) mutable.value = mutable.value.copy(busy = false, problem = code(e)) }
        }
    }
    fun cancelPending() {
        val p = pending ?: return
        val cache = mutable.value.cache ?: return
        val epoch = ++generation
        job?.cancel(); pending = null
        mutable.value = mutable.value.copy(busy = true, problem = null)
        job = viewModelScope.launch {
            try {
                val next = cache.copy(session = repository.cancel(cache, p.first))
                if (epoch != generation) return@launch
                repository.save(next)
                if (epoch == generation) mutable.value = TutorUiState(next, false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) mutable.value = mutable.value.copy(busy = false, problem = "CANCEL_UNCONFIRMED") }
        }
    }
    private fun code(e: Exception) = when (e) { is TutorApiException -> e.code; is TutorUnavailable -> "UNSUPPORTED"; is java.io.IOException -> "CONNECTION_OR_STORAGE"; else -> "INVALID_OR_UNREADABLE_DATA" }
}
