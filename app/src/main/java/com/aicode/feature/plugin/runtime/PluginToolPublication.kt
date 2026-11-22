package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.domain.PluginContribution
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginTool
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginToolPublication @Inject constructor(
    @param:ApplicationContext private val app: Context,
    private val plugins: PluginManager,
    private val registry: ToolRegistry,
    private val gateway: PluginHostGateway,
    private val runtime: PluginRuntimeManager
) {
    private val published = mutableMapOf<String, PluginAgentTool>()

    suspend fun observe() {
        plugins.ensureLoaded()
        try { plugins.snapshot.collect { update(plugins.tools()) } }
        finally { update(emptyList()) }
    }

    private fun update(sources: List<PluginContribution<PluginTool>>) {
        val desired = sources.groupBy { PluginAgentTool.toolName(it.capture.id, it.value.localId) }
            .mapNotNull { (name, items) -> items.singleOrNull()?.let { name to it } }.toMap()
        val iterator = published.iterator()
        while (iterator.hasNext()) {
            val (name, tool) = iterator.next()
            val current = desired[name]?.capture
            val prior = tool.source.capture
            if (current == null || current.installRevision != prior.installRevision || current.activationEpoch != prior.activationEpoch ||
                current.contentHash != prior.contentHash) {
                registry.unregisterOwned(tool)
                iterator.remove()
            }
        }
        desired.forEach { (name, source) ->
            if (name !in published) {
                val tool = PluginAgentTool(app, source, gateway, runtime)
                if (registry.registerIfVacant(tool)) published[name] = tool
            }
        }
    }
}
