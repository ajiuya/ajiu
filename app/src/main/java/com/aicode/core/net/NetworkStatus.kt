package com.aicode.core.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NetworkStatus {
    enum class Transport {
        WIFI, CELLULAR, ETHERNET, VPN, BLUETOOTH, OTHER
    }

    data class Snapshot(
        val isOnline: Boolean = true,
        val recoveryVersion: Long = 0,
        val networkGeneration: Long = 0,
        val hasInternet: Boolean? = null,
        val validated: Boolean? = null,
        val blocked: Boolean? = null,
        val transports: Set<Transport> = emptySet()
    ) {
        fun diagnosticSummary(): String =
            "online=$isOnline recoveryVersion=$recoveryVersion networkGeneration=$networkGeneration " +
                "internet=$hasInternet validated=$validated blocked=$blocked " +
                "transports=${transports.sortedBy { it.name }.joinToString(",") { it.name }}"
    }

    internal val store = NetworkStatusStore()

    val states: StateFlow<Snapshot> = store.states

    val isOnline: Boolean
        get() = states.value.isOnline

    fun update(online: Boolean) {
        store.update(online)
    }
}

internal class NetworkStatusStore(initial: NetworkStatus.Snapshot = NetworkStatus.Snapshot()) {
    private val mutableStates = MutableStateFlow(initial)
    val states: StateFlow<NetworkStatus.Snapshot> = mutableStates.asStateFlow()

    @Synchronized
    fun update(online: Boolean) {
        val previous = mutableStates.value
        mutableStates.value = previous.copy(
            isOnline = online,
            recoveryVersion = if (online && !previous.isOnline) {
                previous.recoveryVersion.incrementVersion()
            } else {
                previous.recoveryVersion
            }
        )
    }

    @Synchronized
    fun updateNetwork(
        online: Boolean,
        networkChanged: Boolean,
        hasInternet: Boolean?,
        validated: Boolean?,
        blocked: Boolean?,
        transports: Set<NetworkStatus.Transport>
    ): Pair<NetworkStatus.Snapshot, NetworkStatus.Snapshot> {
        val previous = mutableStates.value
        val snapshot = NetworkStatus.Snapshot(
            isOnline = online,
            recoveryVersion = if (online && (!previous.isOnline || networkChanged)) {
                previous.recoveryVersion.incrementVersion()
            } else {
                previous.recoveryVersion
            },
            networkGeneration = if (networkChanged) {
                previous.networkGeneration.incrementVersion()
            } else {
                previous.networkGeneration
            },
            hasInternet = hasInternet,
            validated = validated,
            blocked = blocked,
            transports = transports.toSet()
        )
        mutableStates.value = snapshot
        return previous to snapshot
    }

    private fun Long.incrementVersion(): Long = if (this == Long.MAX_VALUE) this else this + 1
}
