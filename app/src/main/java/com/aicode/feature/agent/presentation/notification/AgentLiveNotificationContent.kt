package com.aicode.feature.agent.presentation.notification

import java.util.Locale

internal object AgentLiveNotificationContent {
    data class Clock(val whenMs: Long, val usesChronometer: Boolean, val shortText: String)

    fun timerText(elapsedMs: Long): String {
        val total = elapsedMs.coerceAtLeast(0L) / 1000L
        val hours = total / 3600L
        val minutes = total % 3600L / 60L
        val seconds = total % 60L
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }

    fun clock(snapshot: AgentLiveNotificationState.Snapshot, nowElapsedMs: Long, nowWallMs: Long): Clock {
        val start = snapshot.startedAtElapsedMs
        if (snapshot.completed) {
            val end = snapshot.finishedAtElapsedMs ?: nowElapsedMs
            return Clock(
                whenMs = nowWallMs - (nowElapsedMs - end).coerceAtLeast(0L),
                usesChronometer = false,
                shortText = timerText(if (start != null) end - start else 0L)
            )
        }
        if (start == null) return Clock(nowWallMs, usesChronometer = false, shortText = "")
        return Clock(
            whenMs = nowWallMs - (nowElapsedMs - start).coerceAtLeast(0L),
            usesChronometer = true,
            shortText = timerText(nowElapsedMs - start)
        )
    }

    fun safeAction(label: String, allowedActions: Map<String, String>, fallback: String): String =
        allowedActions[label] ?: fallback

    fun shortText(
        snapshot: AgentLiveNotificationState.Snapshot,
        runningFallback: String,
        completedText: String,
        failedText: String,
        thinkingText: String,
        endedText: String
    ): String {
        if (snapshot.completed) return when {
            snapshot.failed -> failedText
            !snapshot.completionVerified -> endedText
            else -> completedText
        }
        return snapshot.sessions.map { it.statusLabel }.firstOrNull {
            it.isNotBlank() && it != thinkingText && it != runningFallback
        } ?: snapshot.sessions.firstOrNull()?.statusLabel?.ifBlank { runningFallback } ?: runningFallback
    }

    fun title(snapshot: AgentLiveNotificationState.Snapshot, singleFallback: String, multiFormat: String): String =
        if (snapshot.completed || snapshot.sessions.isEmpty()) singleFallback
        else if (snapshot.sessions.size == 1) snapshot.sessions.first().statusLabel.ifBlank { singleFallback }
        else String.format(Locale.ROOT, multiFormat, snapshot.sessions.size)

    fun body(snapshot: AgentLiveNotificationState.Snapshot, emptyFallback: String, sessionFormat: String): String {
        if (snapshot.completed || snapshot.sessions.isEmpty()) return emptyFallback
        // Session titles may themselves be prompts or commands; only localized actions are display facts.
        return snapshot.sessions.take(10).mapIndexed { index, session ->
            String.format(Locale.ROOT, sessionFormat, index + 1, session.statusLabel.ifBlank { emptyFallback })
        }.joinToString("\n")
    }
}
