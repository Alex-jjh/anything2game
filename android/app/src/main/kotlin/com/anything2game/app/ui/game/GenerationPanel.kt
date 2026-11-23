package com.anything2game.app.ui.game

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.anything2game.app.data.api.ApiException
import com.anything2game.app.R
import com.anything2game.app.data.learning.*
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.game.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One refresh per interval while this particular screen is visible. The repository owns job identity and installation. */
@Composable
internal fun GenerationPanel(repository: GenerationRepository, accepts: (GenerationInput) -> Boolean,
    input: @Composable (busy: Boolean, enqueue: (suspend () -> GenerationInput) -> Unit) -> Unit,
    onInstalled: (ContentVersion) -> Unit, scopeKey: String? = null) {
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val filter by rememberUpdatedState(accepts)
    val installed by rememberUpdatedState(onInstalled)
    var tickets by remember { mutableStateOf(emptyList<GenerationTicket>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var autoOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    fun perform(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { error = null; work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { offline = e is ApiException && e.retryable; error = e.message ?: e.javaClass.simpleName }
            finally { busy = false; epoch++ }
        }
    }
    suspend fun install(t: GenerationTicket) {
        val result = repository.download(t.id)
        autoOpen = null
        installed(requireNotNull(result.status?.result).content)
    }
    LaunchedEffect(repository, owner, epoch, scopeKey) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var delayMs = 1200L
            while (true) {
                try {
                    tickets = repository.list().filter { filter(it.request.input) }.asReversed()
                    for (t in tickets.filter { it.state?.isTerminal != true }) repository.refresh(t.id)
                    tickets = repository.list().filter { filter(it.request.input) }.asReversed()
                    tickets.find { it.id == autoOpen && it.state == CompileJobState.READY && !it.installed }?.let {
                        autoOpen = null
                        install(it)
                    }
                    if (tickets.none { it.state?.isTerminal != true }) break
                    delayMs = 1200L
                    error = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    offline = e is ApiException && e.retryable
                    error = e.message ?: e.javaClass.simpleName
                    delayMs = (delayMs * 2).coerceAtMost(12000)
                }
                delay(delayMs)
            }
        }
    }
    input(busy) { build -> perform { autoOpen = repository.enqueue(build()).id } }
    error?.let { Text(if (offline) stringResource(R.string.job_offline) + " " + it else stringResource(R.string.job_problem, it), color = A2g.skin.wrong) }
    tickets.filterNot { it.installed }.forEach { t ->
        key(t.id) { PaperCard {
            (t.request.input as? GenerationInput.ConceptGame)?.let { Text(it.topic, style = MaterialTheme.typography.titleMedium) }
            val state = t.state
            val label = when (state) {
                CompileJobState.READY -> R.string.job_ready
                CompileJobState.FAILED -> R.string.job_failed
                CompileJobState.CANCELLED -> R.string.job_cancelled
                CompileJobState.PARTIAL -> R.string.job_partial
                else -> when (t.status?.stage) {
                    GenerationStage.KNOWLEDGE -> R.string.job_knowledge
                    GenerationStage.EXPERIENCE -> R.string.job_experience
                    GenerationStage.VALIDATION -> R.string.job_validation
                    GenerationStage.PACKAGE -> R.string.job_package
                    null -> if (state == null) R.string.job_waiting else R.string.job_queued
                }
            }
            Text(stringResource(label), Modifier.testTag("generation_status"))
            val problem = t.admissionError ?: t.status?.error
            problem?.let { Text(if (it.code == "UNSUPPORTED_TOPIC") stringResource(R.string.job_unsupported) else it.message, color = A2g.skin.wrong) }
            t.status?.modelId?.let { Text(stringResource(if (t.status.fixture) R.string.games_fixture else R.string.games_generated, it)) }
            when {
                state == CompileJobState.READY -> Button({ perform { install(t) } }, enabled = !busy) { Text(stringResource(R.string.job_install)) }
                state == CompileJobState.FAILED || state == CompileJobState.CANCELLED -> OutlinedButton({ perform { autoOpen = repository.retry(t.id).id } }, enabled = !busy) { Text(stringResource(R.string.home_retry)) }
                state?.isTerminal != true -> OutlinedButton({ perform { autoOpen = null; repository.cancel(t.id) } }, enabled = !busy) { Text(stringResource(R.string.import_cancel)) }
            }
        } }
    }
}
