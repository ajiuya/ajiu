package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.settings.domain.model.ModelContextPolicy
import kotlin.math.exp

internal class GenerationSpeedTracker {
    companion object {
        const val UPDATE_INTERVAL_MS = 250L
        private const val WINDOW_MS = 3_000L
        private const val MIN_SAMPLE_MS = 1_000L
        private const val IDLE_MS = 1_000L
        private const val SMOOTHING_MS = 1_000.0
    }

    private data class Sample(val timeMillis: Long, val tokens: Int)
    private data class Characters(val ascii: Int, val other: Int)

    private val samples = ArrayDeque<Sample>()
    private val toolCharacters = mutableMapOf<String, Characters>()
    private var startedAt: Long? = null
    private var lastContentAt = 0L
    private var asciiChars = 0
    private var otherChars = 0
    private var lastSampleAt: Long? = null
    private var displayedSpeed: AgentEvent.GenerationSpeed? = null

    @Synchronized
    fun begin(nowMillis: Long) {
        if (startedAt != null) return
        startedAt = nowMillis
        samples.addLast(Sample(nowMillis, estimatedTokens()))
    }

    @Synchronized
    fun append(text: String, nowMillis: Long) {
        if (text.isEmpty()) return
        text.forEach { if (it.code < 128) asciiChars++ else otherChars++ }
        contentReceived(nowMillis)
    }

    @Synchronized
    fun appendToolArguments(id: String, ascii: Int, other: Int, nowMillis: Long) {
        val characters = Characters(ascii, other)
        val previous = toolCharacters.put(id, characters)
        if (characters == previous) return
        asciiChars += ascii - (previous?.ascii ?: 0)
        otherChars += other - (previous?.other ?: 0)
        contentReceived(nowMillis)
    }

    private fun contentReceived(nowMillis: Long) {
        begin(nowMillis)
        lastContentAt = nowMillis
        // 首包到达前的产量无法归属到后续观察窗口，同一时刻收到的块共同作为基线。
        if (lastSampleAt == null && startedAt == nowMillis) {
            samples[0] = Sample(nowMillis, estimatedTokens())
        }
    }

    @Synchronized
    fun sample(nowMillis: Long): AgentEvent.GenerationSpeed? {
        val start = startedAt ?: return null
        if (nowMillis - start < MIN_SAMPLE_MS) return null
        val previousAt = lastSampleAt
        if (previousAt != null && nowMillis <= previousAt) return displayedSpeed
        val tokens = estimatedTokens()
        while (samples.size > 1 && samples.elementAt(1).timeMillis <= nowMillis - WINDOW_MS) {
            samples.removeFirst()
        }
        val baseline = samples.first()
        val elapsed = (nowMillis - baseline.timeMillis).coerceAtLeast(MIN_SAMPLE_MS)
        val measured = (tokens - baseline.tokens).coerceAtLeast(0) * 1_000.0 / elapsed
        // 接收块可能被网络缓冲后集中交付；显示不把一次短突发当成模型的瞬时生成速度。
        val previous = displayedSpeed?.tokensPerSecond
        val rate = when {
            nowMillis - lastContentAt >= IDLE_MS -> 0.0
            previous == null || previousAt == null -> measured
            else -> previous + (measured - previous) * (1 - exp(-(nowMillis - previousAt) / SMOOTHING_MS))
        }
        samples.addLast(Sample(nowMillis, tokens))
        lastSampleAt = nowMillis
        return AgentEvent.GenerationSpeed(rate, estimated = true).also { displayedSpeed = it }
    }

    @Synchronized
    fun finish(outputTokens: Int, requestDurationMillis: Long?): AgentEvent.GenerationSpeed? {
        val elapsed = requestDurationMillis ?: return null
        if (elapsed < UPDATE_INTERVAL_MS) return null
        val tokens = outputTokens.takeIf { it > 0 } ?: estimatedTokens()
        if (tokens <= 0) return null
        return AgentEvent.GenerationSpeed(tokens * 1_000.0 / elapsed, estimated = outputTokens <= 0)
    }

    @Synchronized
    fun reset() {
        samples.clear()
        toolCharacters.clear()
        startedAt = null
        lastContentAt = 0L
        asciiChars = 0
        otherChars = 0
        lastSampleAt = null
        displayedSpeed = null
    }

    private fun estimatedTokens(): Int = ModelContextPolicy.estimateTokens(asciiChars) + otherChars
}
