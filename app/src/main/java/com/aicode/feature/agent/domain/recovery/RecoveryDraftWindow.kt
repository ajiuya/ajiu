package com.aicode.feature.agent.domain.recovery

internal class RecoveryDraftWindow(private val intervalMs: Long = RecoveryRepository.DRAFT_MINIMUM_INTERVAL_MS) {
    private var savedStep: RecoveryStep? = null
    private var savedAtMs: Long? = null

    fun isDue(step: RecoveryStep, nowMs: Long): Boolean {
        val savedAt = savedAtMs ?: return true
        return savedStep != step || nowMs < savedAt || nowMs - savedAt >= intervalMs
    }

    fun saved(step: RecoveryStep, startedAtMs: Long) {
        savedStep = step
        savedAtMs = startedAtMs
    }
}
