package com.aicode.feature.player.presentation

internal enum class PlayerServiceStartDecision { ACCEPT, IGNORE_STALE, STOP_ORPHAN }
internal fun playerServiceStartDecision(requestedId: String?, currentId: String?, background: Boolean,
    foregroundStarted: Boolean): PlayerServiceStartDecision = when {
    requestedId != null && requestedId == currentId && background -> PlayerServiceStartDecision.ACCEPT
    currentId != null && background && foregroundStarted -> PlayerServiceStartDecision.IGNORE_STALE
    else -> PlayerServiceStartDecision.STOP_ORPHAN
}
