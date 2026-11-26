package com.aicode.testing

import java.util.concurrent.CancellationException

internal class ChrootVerificationForeground {
    data class State(
        val started: Boolean = false,
        val resumed: Boolean = false,
        val focused: Boolean = false,
        val established: Boolean = false,
        val lost: Boolean = false,
        val closing: Boolean = false,
        val closed: Boolean = false
    ) {
        val ready: Boolean get() = started && resumed && focused
        val usable: Boolean get() = ready && established && !lost && !closing && !closed
    }

    private val lock = Any()
    @Volatile private var state = State()
    private var stopCallback: (() -> Unit)? = null
    private var stopDispatched = false

    fun snapshot(): State = state

    fun requireUsable() = VerificationAssertion.requireTrue(state.usable, "verification_foreground_required")

    fun checkCurrent() {
        if (!state.usable) throw CancellationException("verification_foreground_required")
    }

    fun onStarted() = update { it.copy(started = true) }
    fun onResumed() = update { it.copy(resumed = true) }
    fun onWindowFocusChanged(focused: Boolean) = update { it.copy(focused = focused) }
    fun onPaused() = update { it.copy(resumed = false) }
    fun onStopped() = update { it.copy(started = false, resumed = false, focused = false) }
    fun onDestroyed() = update { it.copy(started = false, resumed = false, focused = false) }
    fun beginClosing() = update { it.copy(closing = true) }
    fun markClosed() = update {
        check(it.closing)
        it.copy(started = false, resumed = false, focused = false, closed = true)
    }

    fun attachStopCallback(callback: () -> Unit) {
        val pending = synchronized(lock) {
            check(stopCallback == null)
            stopCallback = callback
            takeStopCallback()
        }
        pending?.invoke()
    }

    fun detachStopCallback() {
        synchronized(lock) { stopCallback = null }
    }

    private fun update(change: (State) -> State) {
        val pending = synchronized(lock) {
            val previous = state
            val next = change(previous)
            state = next.copy(
                established = previous.established || !next.closing && !next.closed && next.ready,
                lost = previous.lost || previous.established && !next.closing && !next.closed && !next.ready
            )
            takeStopCallback()
        }
        pending?.invoke()
    }

    private fun takeStopCallback(): (() -> Unit)? {
        if (!state.lost || state.closing || stopDispatched) return null
        val callback = stopCallback ?: return null
        stopDispatched = true
        return callback
    }
}
