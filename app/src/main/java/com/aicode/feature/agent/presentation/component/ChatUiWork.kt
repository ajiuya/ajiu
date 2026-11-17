package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.aicode.feature.agent.presentation.preview.LocalUiWorkVisible

@Composable
internal fun chatUiWorkActive(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return LocalUiWorkVisible.current && state.isAtLeast(Lifecycle.State.STARTED)
}
