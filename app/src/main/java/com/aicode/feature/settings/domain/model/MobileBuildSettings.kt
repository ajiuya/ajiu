package com.aicode.feature.settings.domain.model

data class MobileBuildSettings(
    val enabled: Boolean = false,
    val gradleHeapMb: Int = 3072,
    val daemonEnabled: Boolean = false,
    val parallelEnabled: Boolean = false,
    val maxWorkers: Int = 1,
    val testHeapMb: Int = 1024
) {
    companion object {
        val GRADLE_HEAP_OPTIONS = listOf(2048, 2560, 3072)
        val WORKER_OPTIONS = (1..4).toList()
        val TEST_HEAP_OPTIONS = listOf(512, 768, 1024, 1536, 2048)
    }
}
