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
    fun choose(option: String) {
        val c = mutable.value.cache ?: return
        val q = c.session.questions[c.questionIndex]
        if (c.session.assessment.isNotEmpty() || (option != UNKNOWN_ANSWER && q.options.none { it.id == option })) return
        execute { old, _ -> old.copy(draftAnswers = old.draftAnswers + (q.id to option)) }
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
    fun turn(intent: TutorIntent, question: String = "") {
        if (intent == TutorIntent.QUESTION && (question.isBlank() || question.length > 1000)) return
        execute { c, requestId -> c.copy(session = repository.turn(c, intent, question.trim(), requestId)) }
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
