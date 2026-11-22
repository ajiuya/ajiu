package com.aicode.feature.plugin.runtime

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

sealed interface PluginChatUiAction {
    data class Open(val sessionId: String) : PluginChatUiAction
    data class SetDraft(val sessionId: String?, val text: String, val append: Boolean) : PluginChatUiAction
}

class PluginChatUiRequest internal constructor(
    val id: String,
    val pluginId: String,
    val workspacePath: String,
    val callerSessionId: String?,
    val action: PluginChatUiAction,
    private val check: () -> Unit,
    private val finish: (String, Boolean) -> Unit
) {
    fun checkCurrent() = check()
    fun complete(applied: Boolean) = finish(id, applied)
}

@Singleton
class PluginChatUiRequests @Inject constructor() {
    private class Entry(val owner: PluginRuntimeOwner) {
        var active = true
        val applied = CompletableDeferred<Boolean>()
    }
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val channel = Channel<PluginChatUiRequest>(16)
    val requests: Flow<PluginChatUiRequest> = channel.receiveAsFlow()
    private var receiver: Any? = null

    fun attach(): AutoCloseable {
        val identity = Any()
        synchronized(lock) {
            check(receiver == null) { "A chat UI receiver is already attached" }
            receiver = identity
        }
        return AutoCloseable {
            synchronized(lock) {
                if (receiver === identity) {
                    receiver = null
                    entries.values.forEach { it.active = false; it.applied.complete(false) }
                    entries.clear()
                    while (channel.tryReceive().isSuccess) Unit
                }
            }
        }
    }

    internal suspend fun submit(context: PluginInvocationContext, action: PluginChatUiAction): String = context.whileCurrent {
        context.checkCurrent()
        val id = UUID.randomUUID().toString()
        val entry = Entry(PluginRuntimeOwner(context))
        synchronized(lock) {
            if (receiver == null) throw PluginHostException("CHAT_UI_UNAVAILABLE", "Chat UI is not available")
            if (entries.size >= 16) throw PluginHostException("CHAT_BUSY", "Chat UI request queue is full")
            entries[id] = entry
            val request = PluginChatUiRequest(id, context.capture.id, context.workspacePath, context.sessionId, action, {
                entry.owner.checkCurrent()
                synchronized(lock) {
                    if (!entry.active || entries[id] !== entry) throw CancellationException("Chat UI request expired")
                }
            }, ::complete)
            if (!channel.trySend(request).isSuccess) {
                entries.remove(id)
                throw PluginHostException("CHAT_BUSY", "Chat UI request queue is full")
            }
        }
        try {
            if (withTimeoutOrNull(30_000) { entry.applied.await() } != true) {
                throw PluginHostException("CHAT_UI_NOT_APPLIED", "Chat UI did not apply this request")
            }
            context.checkCurrent()
            id
        } finally { complete(id, false) }
    }

    internal fun cancel(context: PluginInvocationContext, id: String): Boolean {
        context.checkCurrent()
        val entry = synchronized(lock) { entries[id] } ?: return false
        entry.owner.requireCaller(context)
        complete(id, false)
        return true
    }

    private fun complete(id: String, applied: Boolean) {
        synchronized(lock) {
            val entry = entries.remove(id) ?: return
            entry.active = false
            entry.applied.complete(applied && runCatching { entry.owner.checkCurrent() }.isSuccess)
        }
    }
}
