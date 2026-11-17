package com.aicode.feature.agent.domain.context

enum class CompactionPhase {
    PREPARING,
    SUMMARIZING,
    MERGING,
    VALIDATING,
    SAVING,
    COMPLETED,
    UNCHANGED,
    FAILED,
    CANCELLED,
}

enum class CompactionStreamState { NONE, WAITING, RECEIVING, RETRYING }

/** 只包含投影原文的已验证处理量，不包含正文、思考或上游错误；等待模型时百分比不前进。 */
data class CompactionProgress(
    val phase: CompactionPhase = CompactionPhase.PREPARING,
    val percent: Int = 0,
    val completedCharacters: Long = 0,
    val totalCharacters: Long = 0,
    val completedBlocks: Int = 0,
    val totalBlocks: Int? = null,
    val streamState: CompactionStreamState = CompactionStreamState.NONE,
    val attempts: Int = 0,
    val maxAttempts: Int = 0,
    val receivedCharacters: Long = 0,
    val elapsedMs: Long = 0,
    val lastErrorCode: String? = null,
    val completedMerges: Int = 0,
    val mergeLevel: Int = 0,
) {
    val isTerminal: Boolean
        get() = phase == CompactionPhase.COMPLETED || phase == CompactionPhase.UNCHANGED ||
            phase == CompactionPhase.FAILED || phase == CompactionPhase.CANCELLED
}

object CompactionProgressPolicy {
    const val SUMMARIZING_MAX_PERCENT = 90
    const val SAVING_PERCENT = 95

    fun summarizing(totalCharacters: Long): CompactionProgress = CompactionProgress(
        phase = CompactionPhase.SUMMARIZING,
        totalCharacters = totalCharacters.coerceAtLeast(0),
    )

    fun summarized(
        previous: CompactionProgress,
        completedCharacters: Long,
        completedBlocks: Int,
        totalBlocks: Int? = previous.totalBlocks,
    ): CompactionProgress {
        if (previous.isTerminal) return previous
        val completed = completedCharacters.coerceIn(previous.completedCharacters, previous.totalCharacters)
        val blocks = completedBlocks.coerceAtLeast(previous.completedBlocks)
        return previous.copy(
            phase = CompactionPhase.SUMMARIZING,
            percent = sourcePercent(completed, previous.totalCharacters),
            completedCharacters = completed,
            completedBlocks = blocks,
            totalBlocks = totalBlocks?.coerceAtLeast(blocks),
        )
    }

    fun withPhase(previous: CompactionProgress, phase: CompactionPhase): CompactionProgress {
        if (previous.isTerminal) return previous
        return previous.copy(
            phase = phase,
            percent = when (phase) {
                CompactionPhase.COMPLETED, CompactionPhase.UNCHANGED -> 100
                CompactionPhase.SAVING -> maxOf(previous.percent, SAVING_PERCENT)
                else -> previous.percent
            },
        )
    }

    private fun sourcePercent(completed: Long, total: Long): Int {
        if (total <= 0) return 0
        var low = 0
        var high = SUMMARIZING_MAX_PERCENT
        while (low < high) {
            val candidate = low + (high - low + 1) / 2
            // 先除后乘，避免超大历史在计算 ceil(total * candidate / 90) 时溢出。
            val minimum = (total / SUMMARIZING_MAX_PERCENT) * candidate +
                ((total % SUMMARIZING_MAX_PERCENT) * candidate + SUMMARIZING_MAX_PERCENT - 1) / SUMMARIZING_MAX_PERCENT
            if (completed >= minimum) low = candidate else high = candidate - 1
        }
        return low
    }
}
