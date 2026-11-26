package com.aicode.feature.agent.domain.prompt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionEntry
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemPromptSourceIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val skills = mockk<SkillRepository>()
    private val agents = mockk<AgentDefinitionRepository>()
    private var source = "host-a:profile-a:uuid-a"
    private var revision = 0L
    private var skillScans = 0
    private var agentScans = 0
    private var skillFailure = false
    private var agentFailure = false
    private var duringSkills: () -> Unit = {}
    private var duringAgents: () -> Unit = {}

    private fun provider(): Pair<SystemPromptProvider, AgentContext> {
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns temporary.newFolder("global")
        val memory = mockk<MemoryRepository>()
        every { memory.listMemories(any()) } returns emptyList()
        val catalog = mockk<PromptFragmentCatalog>()
        every { catalog.renderStatic(any()) } returns "{{AICODE_SKILLS}}\n{{AICODE_SUBAGENTS}}"
        every { skills.sourceIdentity() } answers { "$source:$revision" }
        every { skills.checkSourceIdentity(any()) } answers {
            if (firstArg<String>() != "$source:$revision") throw CancellationException("changed")
        }
        every { skills.listSkills() } answers {
            skillScans++
            if (skillFailure) throw CancellationException("skill scan cancelled")
            val result = listOf(Skill("skill-$source", "desc", instructions = "full body"))
            duringSkills()
            result
        }
        every { agents.definitionsVersion } answers { revision }
        every { agents.sourceIdentity() } answers { "$source:$revision" }
        every { agents.checkSourceIdentity(any()) } answers {
            if (firstArg<String>() != "$source:$revision") throw CancellationException("changed")
        }
        every { agents.listEnabled() } answers {
            agentScans++
            if (agentFailure) throw CancellationException("agent scan cancelled")
            val result = listOf(AgentDefinitionEntry(AgentDefinition(name = "agent-$source", description = "desc", prompt = "full prompt"), AgentDefinitionScope.GLOBAL))
            duringAgents()
            result
        }
        val context = AgentContext(null, null, temporary.newFolder("project").path, null, sessionId = "same-session")
        val settings = ContainerSettingsRepository(mockk<DataStore<Preferences>> {
            every { data } returns flowOf(emptyPreferences())
        })
        return SystemPromptProvider(mockk<Context>(), skills, memory, catalog, installer, agents, ExecutionModeHolder(), settings) to context
    }

    @Test fun sameSessionAndProjectAreSeparatedByEndpointProfileUuidAndRevision() {
        val (provider, context) = provider()
        val first = provider.build(context)
        assertTrue(first.contains("skill-host-a:profile-a:uuid-a"))
        assertEquals(first, provider.build(context))
        assertEquals(1, skillScans)
        assertEquals(1, agentScans)
        source = "host-b:profile-b:uuid-b"
        val second = provider.build(context)
        assertFalse(second.contains("host-a:profile-a:uuid-a"))
        assertTrue(second.contains("agent-host-b:profile-b:uuid-b"))
        source = "host-a:profile-a:uuid-new"
        provider.build(context)
        revision++
        provider.build(context)
        assertEquals(4, skillScans)
        assertEquals(4, agentScans)
    }

    @Test fun cancelledSkillScanIsRethrownAndNotCachedAsEmpty() {
        val (provider, context) = provider()
        skillFailure = true
        assertThrows(CancellationException::class.java) { provider.build(context) }
        skillFailure = false
        assertTrue(provider.build(context).contains("skill-$source"))
        assertEquals(2, skillScans)
    }

    @Test fun cancelledAgentScanIsRethrownAndNotCachedAsEmpty() {
        val (provider, context) = provider()
        agentFailure = true
        assertThrows(CancellationException::class.java) { provider.build(context) }
        agentFailure = false
        assertTrue(provider.build(context).contains("agent-$source"))
        assertEquals(2, agentScans)
        assertEquals(1, skillScans)
    }

    @Test fun identityChangeDuringSkillReadCannotPublishOrReuseOldKey() {
        val (provider, context) = provider()
        duringSkills = { source = "host-b:profile-b:uuid-b" }
        assertThrows(CancellationException::class.java) { provider.build(context) }
        duringSkills = {}
        assertTrue(provider.build(context).contains("skill-host-b:profile-b:uuid-b"))
        assertEquals(2, skillScans)
    }

    @Test fun identityChangeDuringAgentReadCannotPublishOldDefinitions() {
        val (provider, context) = provider()
        duringAgents = { source = "host-b:profile-b:uuid-b" }
        assertThrows(CancellationException::class.java) { provider.build(context) }
        duringAgents = {}
        val result = provider.build(context)
        assertFalse(result.contains("host-a:profile-a:uuid-a"))
        assertTrue(result.contains("agent-host-b:profile-b:uuid-b"))
        verify(exactly = 2) { agents.listEnabled() }
    }
}
