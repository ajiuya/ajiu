package com.aicode.feature.agent.presentation.preview

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

internal data class AppResourceUsage(val cpuPercent: Float? = null, val pssBytes: Long? = null)

internal class AppCpuWindow(private val clockTicksPerSecond: Long?) {
    private var previous: Pair<Long, Long>? = null
    private val deltas = ArrayDeque<Pair<Long, Long>>()
    fun reset() { previous = null; deltas.clear() }
    fun sample(ticks: Long?, nanos: Long): Float? {
        val hz = clockTicksPerSecond?.takeIf { it > 0 } ?: return null
        if (ticks == null || ticks < 0) { reset(); return null }
        val old = previous
        previous = ticks to nanos
        if (old == null) return null
        val cpu = ticks - old.first
        val wall = nanos - old.second
        if (cpu < 0 || wall <= 0) { deltas.clear(); return null }
        deltas.addLast(cpu to wall)
        while (deltas.size > 5) deltas.removeFirst()
        // IRIX: one fully occupied core is 100%, not 100 / number-of-cores.
        return (deltas.sumOf { it.first.toDouble() } / hz / (deltas.sumOf { it.second.toDouble() } / 1_000_000_000.0) * 100)
            .toFloat().takeIf { it.isFinite() && it >= 0 }
    }
}

internal class SystemResourceMonitor {
    private val cpu = AppCpuWindow(runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull())
    fun reset() = cpu.reset()
    fun sampleOnce(): AppResourceUsage {
        val ticks = runCatching { parseSelfCpuTicks(File("/proc/self/stat").readText()) }.getOrNull()
        val pss = runCatching { parseTotalPssKb(File("/proc/self/smaps_rollup").readText()) }.getOrNull()
            ?: runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong().takeIf { it > 0 } }.getOrNull()
        return AppResourceUsage(cpu.sample(ticks, System.nanoTime()), pss?.takeIf { it <= Long.MAX_VALUE / 1024 }?.times(1024))
    }

    companion object {
        internal fun parseSelfCpuTicks(raw: String): Long? {
            val end = raw.lastIndexOf(')')
            if (end < 0) return null
            val tail = raw.substring(end + 1).trim().split(Regex("\\s+"))
            val user = tail.getOrNull(11)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val system = tail.getOrNull(12)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return if (user <= Long.MAX_VALUE - system) user + system else null
        }
        internal fun parseTotalPssKb(rollup: String): Long? {
            var pss: Long? = null
            var swap = 0L
            for (line in rollup.lineSequence()) {
                val key = line.substringBefore(':').trim()
                if (key !in setOf("Pss", "SwapPss")) continue
                val value = line.substringAfter(':', "").trim().split(Regex("\\s+"))
                if (value.size != 2 || value[1] != "kB") return null
                val number = value[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
                if (key == "Pss") pss = number else swap = number
            }
            return pss?.takeIf { it <= Long.MAX_VALUE - swap }?.plus(swap)
        }
    }
}

@Composable
internal fun rememberSystemResourceUsage(active: Boolean): AppResourceUsage {
    val monitor = remember { SystemResourceMonitor() }
    var usage by remember { mutableStateOf(AppResourceUsage()) }
    UiWorkEffect(monitor, visible = active && LocalUiWorkVisible.current) {
        usage = AppResourceUsage()
        withContext(Dispatchers.IO) { monitor.reset() }
        while (true) {
            usage = withContext(Dispatchers.IO) { monitor.sampleOnce() }
            delay(2_000L)
        }
    }
    return usage
}

internal fun showResourceHud(target: ToolPreviewTarget, visible: Boolean): Boolean =
    visible && target.kind == ToolPreviewKind.COMMAND && target.stage == ToolPreviewStage.RUNNING
