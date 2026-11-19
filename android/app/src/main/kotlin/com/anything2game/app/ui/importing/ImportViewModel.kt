package com.anything2game.app.ui.importing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.data.tutor.*
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.DocumentResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ImportUiState(val busy: Boolean = false, val uploaded: Boolean = false, val error: String? = null, val ready: String? = null)

class ImportViewModel(private val repository: TutorRepository, private val request: ImportRequest, private val saved: SavedStateHandle) : ViewModel() {
    private var document: DocumentResponse? = saved.get<String>("documentId")?.let { id ->
        DocumentResponse(id, saved["documentVersion"] ?: "", saved["pageCount"] ?: 0, saved["documentTitle"] ?: request.title)
    }
    private val mutable = MutableStateFlow(ImportUiState(uploaded = document != null))
    val state = mutable.asStateFlow()
    private var epoch = 0
    private var job: Job? = null
    private val requestId = saved.get<String>("requestId") ?: UUID.randomUUID().toString().also { saved["requestId"] = it }
    fun start() {
        if (mutable.value.busy) return
        val current = ++epoch
        mutable.value = mutable.value.copy(busy = true, error = null, ready = null)
        job = viewModelScope.launch {
            try {
                val doc = document ?: repository.upload(request).also { uploaded ->
                    if (current != epoch) return@launch
                    document = uploaded
                    saved["documentId"] = uploaded.documentId; saved["documentVersion"] = uploaded.documentVersion
                    saved["pageCount"] = uploaded.pageCount; saved["documentTitle"] = uploaded.title
                    mutable.value = mutable.value.copy(uploaded = true)
                }
                val session = repository.create(doc, requestId)
                if (current != epoch) return@launch
                repository.save(TutorCache(session))
                if (current == epoch) mutable.value = ImportUiState(uploaded = true, ready = session.sessionId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == epoch) mutable.value = mutable.value.copy(busy = false, error = (e as? TutorApiException)?.code ?: "CONNECTION_OR_FILE_ERROR") }
        }
    }
    fun cancel() {
        epoch++; job?.cancel()
        mutable.value = mutable.value.copy(busy = false, ready = null, error = null)
    }
}
