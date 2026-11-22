package com.aicode.feature.plugin.runtime

object PluginProviderIds {
    const val ID_PREFIX = "plugin:"
    private val pluginPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val localPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

    fun idOf(pluginId: String, providerId: String): String {
        require(pluginPattern.matches(pluginId) && localPattern.matches(providerId))
        return "$ID_PREFIX$pluginId:$providerId"
    }

    fun isPluginId(id: String): Boolean = id.startsWith(ID_PREFIX)
    fun pluginIdOf(id: String): String? = split(id)?.first
    fun localIdOf(id: String): String? = split(id)?.second

    private fun split(id: String): Pair<String, String>? {
        if (!isPluginId(id)) return null
        val parts = id.removePrefix(ID_PREFIX).split(':')
        if (parts.size != 2 || !pluginPattern.matches(parts[0]) || !localPattern.matches(parts[1])) return null
        return parts[0] to parts[1]
    }
}
