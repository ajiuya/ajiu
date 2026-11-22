package com.aicode.feature.agent.domain.tool

import java.util.Collections
import javax.inject.Singleton

@Singleton
class ToolRegistry {
    // LinkedHashMap 保留注册顺序（内置工具固定顺序 + MCP 动态追加），避免工具增删后
    // 前缀顺序漂移打断隐式前缀缓存；synchronizedMap 保证并发注册/读取安全。
    private val tools = Collections.synchronizedMap(LinkedHashMap<String, AgentTool>())

    fun register(name: String, tool: AgentTool) {
        tools[name] = tool
    }

    fun getTool(name: String): AgentTool? {
        return tools[name]
    }

    internal fun registerIfVacant(tool: AgentTool): Boolean = synchronized(tools) {
        if (tool.name in tools) false else { tools[tool.name] = tool; true }
    }

    internal fun unregisterOwned(tool: AgentTool) = synchronized(tools) {
        if (tools[tool.name] === tool) tools.remove(tool.name)
        Unit
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getToolNames(): Set<String> {
        return synchronized(tools) { tools.keys.toSet() }
    }

    /** 返回可用工具列表。 */
    fun getAvailableTools(): List<AgentTool> {
        synchronized(tools) {
            return tools.values.toList()
        }
    }

    fun hasTool(name: String): Boolean {
        return tools.containsKey(name)
    }
}

internal fun ToolRegistry.forInvocation(name: String, offered: List<AgentTool>): AgentTool? {
    val registered = getTool(name) ?: return null
    // Reinstalling with the same name does not authorize a model call offered by the old installation.
    if (name.startsWith("plugin__") && offered.none { it === registered }) return null
    return registered
}
