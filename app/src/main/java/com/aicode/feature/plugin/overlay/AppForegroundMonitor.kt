package com.aicode.feature.plugin.overlay

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AppForegroundMonitor @Inject constructor() {
    private val mutableForeground = MutableStateFlow(false)
    val isForeground = mutableForeground.asStateFlow()
    init {
        Handler(Looper.getMainLooper()).post {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            mutableForeground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { mutableForeground.value = true }
                override fun onStop(owner: LifecycleOwner) { mutableForeground.value = false }
            })
        }
    }
    fun current(): Boolean = isForeground.value
}
