package com.aicode.feature.agent.domain.container.chroot

internal object ChrootRuntimeTimeouts {
    const val CLEANUP_TIMEOUT_MS = 10_000L
    const val CLEANUP_AWAIT_TIMEOUT_MS = CLEANUP_TIMEOUT_MS + 2_000L
}
