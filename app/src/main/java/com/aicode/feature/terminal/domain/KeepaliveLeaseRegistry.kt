package com.aicode.feature.terminal.domain

internal class KeepaliveLeaseRegistry(private val nowMillis: () -> Long) {
    private data class Lease(val cpu: Boolean, val network: Boolean, val expiresAt: Long? = null)

    data class Snapshot(
        val persistent: Boolean,
        val ownerCount: Int,
        val serviceRequired: Boolean,
        val cpuRequired: Boolean,
        val wifiRequired: Boolean,
        val foreground: Boolean,
        val startPending: Boolean,
        val suspended: Boolean
    ) {
        val protected: Boolean get() = serviceRequired && foreground
    }

    private val owners = mutableMapOf<String, Lease>()
    private var persistent = false
    private var persistentRevision = 0L
    private var foreground = false
    private var startPending = false
    private var suspended = false

    @Synchronized
    fun acquire(owner: String, cpu: Boolean, network: Boolean) {
        require(owner.isNotBlank())
        owners[owner] = Lease(cpu, network)
    }

    @Synchronized
    fun acquireTimed(owner: String, durationMillis: Long): Long {
        require(owner.isNotBlank())
        require(durationMillis > 0)
        val expiresAt = nowMillis() + durationMillis
        owners[owner] = Lease(cpu = true, network = true, expiresAt = expiresAt)
        return expiresAt
    }

    @Synchronized
    fun expire(owner: String, expiresAt: Long): Boolean {
        if (owners[owner]?.expiresAt != expiresAt || nowMillis() < expiresAt) return false
        owners.remove(owner)
        return true
    }

    @Synchronized
    fun release(owner: String) {
        owners.remove(owner)
    }

    @Synchronized
    fun setPersistent(enabled: Boolean) {
        persistent = enabled
        persistentRevision++
    }

    @Synchronized
    fun persistentRevision(): Long = persistentRevision

    @Synchronized
    fun restorePersistent(expectedRevision: Long): Boolean {
        if (persistentRevision != expectedRevision) return false
        persistent = true
        persistentRevision++
        return true
    }

    @Synchronized
    fun setSuspended(value: Boolean) {
        suspended = value
    }

    @Synchronized
    fun markStartRequested() {
        startPending = true
    }

    @Synchronized
    fun markForeground() {
        foreground = true
        startPending = false
    }

    @Synchronized
    fun markUnavailable() {
        foreground = false
        startPending = false
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val now = nowMillis()
        owners.entries.removeAll { (_, lease) -> lease.expiresAt?.let { it <= now } == true }
        val required = !suspended && (persistent || owners.isNotEmpty())
        return Snapshot(
            persistent = persistent,
            ownerCount = owners.size,
            serviceRequired = required,
            cpuRequired = required && owners.values.any { it.cpu },
            wifiRequired = required && owners.values.any { it.network },
            foreground = foreground,
            startPending = startPending,
            suspended = suspended
        )
    }
}
