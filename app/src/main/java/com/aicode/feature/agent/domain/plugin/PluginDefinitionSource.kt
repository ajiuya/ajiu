package com.aicode.feature.agent.domain.plugin

import com.aicode.feature.agent.domain.skill.SkillEntry
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.AgentDefinitionEntry
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginContribution
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class PluginDefinitionOrigin(
    val pluginId: String,
    val localId: String,
    val installRevision: String,
    val contentHash: String,
    val activationEpoch: Long
)

class PluginDefinitionReadOnlyException : IllegalArgumentException("Plugin definitions are read-only")

@Singleton
class PluginDefinitionSource @Inject constructor(private val manager: PluginManager) {
    // The manager is loaded by application startup; identity checks must never load or hash on the UI thread.
    val revision: Long get() = manager.revision.value

    fun skills(): List<SkillEntry> = read(manager::skills) { contribution ->
        SkillEntry(contribution.value.skill, SkillScope.PLUGIN, contribution.origin(contribution.value.localId))
    }

    fun agents(): List<AgentDefinitionEntry> = read(manager::agents) { contribution ->
        AgentDefinitionEntry(contribution.value.definition, AgentDefinitionScope.PLUGIN, contribution.origin(contribution.value.localId))
    }

    private fun <T, R> read(
        contributions: () -> List<PluginContribution<T>>,
        transform: (PluginContribution<T>) -> R
    ): List<R> {
        val snapshot = manager.snapshot.value
        checkRevision(snapshot)
        if (snapshot.plugins.none { it.isActive }) return emptyList<R>().also { checkRevision(snapshot) }
        val captured = contributions().toList()
        checkRevision(snapshot)
        val captures = captured.map { it.capture }.distinct()
        captures.forEach { capture ->
            val installed = snapshot.plugins.firstOrNull { it.record.id == capture.id }
            if (installed == null || !installed.isActive || installed.record != capture.record) {
                throw PluginCaptureException(PluginError(PluginErrorCode.STALE_CAPTURE))
            }
            capture.checkCurrent()
        }
        val result = captured.map(transform)
        captures.forEach { it.checkCurrent() }
        checkRevision(snapshot)
        return result
    }

    private fun checkRevision(snapshot: PluginSnapshot) {
        if (revision != snapshot.revision || manager.snapshot.value.revision != snapshot.revision) {
            throw CancellationException("Plugin definition source changed")
        }
    }

    private fun PluginContribution<*>.origin(localId: String): PluginDefinitionOrigin =
        PluginDefinitionOrigin(capture.id, localId, capture.installRevision, capture.contentHash, capture.activationEpoch)
}
