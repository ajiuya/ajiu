package com.aicode.feature.filepreview.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aicode.feature.filepreview.domain.PreviewRequest

@Stable
internal class FilePreviewState {
    var request by mutableStateOf<PreviewRequest?>(null)
        private set
    var revision by mutableStateOf(0)
        private set

    fun open(request: PreviewRequest) {
        this.request = request
        revision++
    }

    fun close() {
        request = null
    }
}

@Composable
internal fun rememberFilePreviewState(sessionId: String?): FilePreviewState = remember(sessionId) { FilePreviewState() }
