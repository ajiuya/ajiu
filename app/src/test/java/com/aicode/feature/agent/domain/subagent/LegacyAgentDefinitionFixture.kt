package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow

internal fun legacyAgentDefinitions(provider: FileAccessProvider): LocalAgentDefinitionSource = mockk<LocalAgentDefinitionSource>().apply {
    every { sourceIdentity() } answers { provider.agentConfigSourceIdentity() }
    every { sourceSignals() } returns emptyFlow()
    coEvery { capture() } returns null
}
