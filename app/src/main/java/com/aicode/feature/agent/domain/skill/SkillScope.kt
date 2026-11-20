package com.aicode.feature.agent.domain.skill

import com.aicode.feature.agent.domain.plugin.PluginDefinitionOrigin

/** 技能的来源作用域。 */
enum class SkillScope { GLOBAL, PROJECT, PLUGIN }

/** 一个生效的技能条目：技能本体及其来源。 */
data class SkillEntry(
    val skill: Skill,
    val scope: SkillScope,
    val pluginOrigin: PluginDefinitionOrigin? = null
)
