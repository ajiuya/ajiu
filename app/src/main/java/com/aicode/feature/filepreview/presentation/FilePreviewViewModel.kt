package com.aicode.feature.filepreview.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.filepreview.data.PreviewSource
import com.aicode.feature.filepreview.data.PreviewSourceFactory
import com.aicode.feature.filepreview.domain.PreviewRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface PreviewLoad {
    data object Loading : PreviewLoad
    data class Ready(val request: PreviewRequest, val source: PreviewSource) : PreviewLoad
    data class Failed(val request: PreviewRequest, val environmentChanged: Boolean = false) : PreviewLoad
}

@HiltViewModel
internal class FilePreviewViewModel @Inject constructor(private val sources: PreviewSourceFactory) : ViewModel() {
    private val _load = MutableStateFlow<PreviewLoad>(PreviewLoad.Loading)
    val load: StateFlow<PreviewLoad> = _load.asStateFlow()
    private var opening: Job? = null
    private var watching: Job? = null
    private var source: PreviewSource? = null
    private var generation = 0

    fun open(request: PreviewRequest) {
        close()
        val current = generation
        _load.value = PreviewLoad.Loading
        opening = viewModelScope.launch {
            var pending: PreviewSource? = null
            try {
                val opened = withContext(Dispatchers.IO) { sources.open(request).also { pending = it } }
                currentCoroutineContext().ensureActive()
                if (generation != current) return@launch
                source = opened
                pending = null
                _load.value = PreviewLoad.Ready(request, opened)
                watching = viewModelScope.launch {
                    opened.valid.first { !it }
                    if (source === opened && generation == current) {
                        source = null
                        _load.value = PreviewLoad.Failed(request, environmentChanged = true)
                        opened.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                if (generation == current && currentCoroutineContext()[Job]?.isActive == true) {
                    _load.value = PreviewLoad.Failed(request, environmentChanged = true)
                }
                throw cancelled
            } catch (_: Exception) {
                if (generation == current) _load.value = PreviewLoad.Failed(request)
            } finally {
                pending?.close()
            }
        }
    }

    fun close() {
        generation++
        watching?.cancel()
        watching = null
        opening?.cancel()
        opening = null
        source?.close()
        source = null
        _load.value = PreviewLoad.Loading
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }
}
