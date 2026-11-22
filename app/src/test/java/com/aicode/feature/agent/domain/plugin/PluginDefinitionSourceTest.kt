package com.aicode.feature.agent.domain.plugin

import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.ContentLoadResult
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginAgent
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginContentLoader
import com.aicode.feature.plugin.domain.PluginContribution
import com.aicode.feature.plugin.domain.PluginContributionRef
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginSkill
import com.aicode.feature.plugin.domain.PluginSnapshot
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.domain.SemVer
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginDefinitionSourceTest {
    @get:Rule val temp = TemporaryFolder()
    private val manager = mockk<PluginManager>()
    private val snapshots = MutableStateFlow(PluginSnapshot())
    private var validation: (PluginCapture) -> Unit = {}
    private var checks = 0
    private lateinit var installed: InstalledPlugin
    private lateinit var capture: PluginCapture
    private lateinit var source: PluginDefinitionSource
    private var skillContributions: List<PluginContribution<PluginSkill>> = emptyList()
    private var agentContributions: List<PluginContribution<PluginAgent>> = emptyList()

    @Before fun setUp() {
        val manifest = PluginManifest(1, "fixture.alpha", LocalizedText.single("Fixture"), SemVer(1, 0, 0),
            "fixture", "https://github.com/jieapi/aicode", LocalizedText.single("Definitions"), "MIT", "^1.0.0",
            skills = listOf(PluginContributionRef("read", "skill.md")),
            agents = listOf(PluginContributionRef("review", "agent.md")))
        File(temp.root, "skill.md").writeText("---\nname: host\ndescription: Skill\n---\nskill body")
        File(temp.root, "agent.md").writeText("---\nname: host\ndescription: Agent\n---\nagent body")
        val content = (PluginContentLoader.load(temp.root, manifest) as ContentLoadResult.Ok).content
        val record = PluginRecord("fixture.alpha", "1.0.0", true, 1,
            archiveSha256 = "archive", manifestSha256 = "manifest", installRevision = "install-one",
            contentHash = "content", fileHashes = emptyMap(), trustHash = "content", activationEpoch = 7)
        installed = InstalledPlugin(record, PluginStatus.ENABLED, manifest, content)
        capture = PluginCapture(record, manifest, content, temp.root, {
            checks++
            validation(it)
        }, {})
        skillContributions = content.skills.map { PluginContribution(capture, it) }
        agentContributions = content.agents.map { PluginContribution(capture, it) }
        snapshots.value = PluginSnapshot(9, listOf(installed))
        every { manager.snapshot } returns snapshots
        every { manager.revision } answers { MutableStateFlow(snapshots.value.revision) }
        every { manager.skills() } answers { skillContributions }
        every { manager.agents() } answers { agentContributions }
        source = PluginDefinitionSource(manager)
    }

    @Test fun nativeParsedDefinitionsAreNamespacedReadonlyAndFullyCheckedTwice() {
        val skill = source.skills().single()
        val agent = source.agents().single()
        assertEquals("fixture.alpha:read", skill.skill.name)
        assertEquals("fixture.alpha:review", agent.definition.name)
        assertEquals(SkillScope.PLUGIN, skill.scope)
        assertEquals(AgentDefinitionScope.PLUGIN, agent.scope)
        assertEquals("skill body", skill.skill.instructions)
        assertEquals("agent body", agent.definition.prompt)
        assertEquals(PluginDefinitionOrigin("fixture.alpha", "read", "install-one", "content", 7), skill.pluginOrigin)
        assertEquals(4, checks)
        verify(exactly = 0) { manager.capture(any()) }
        coVerify(exactly = 0) { manager.ensureLoaded() }
    }

    @Test fun severalDefinitionsFromTheSameOriginalCaptureShareTheFullHashChecks() {
        val first = skillContributions.single()
        skillContributions = skillContributions + PluginContribution(capture,
            first.value.copy(localId = "second", skill = first.value.skill.copy(name = "fixture.alpha:second")))
        assertEquals(listOf("fixture.alpha:read", "fixture.alpha:second"), source.skills().map { it.skill.name })
        assertEquals(2, checks)
        verify(exactly = 0) { manager.capture(any()) }
    }

    @Test fun readingRevisionNeverCapturesHashesOrLoadsTheManager() {
        assertEquals(9L, source.revision)
        assertEquals(0, checks)
        verify(exactly = 0) { manager.skills(); manager.agents(); manager.capture(any()) }
        coVerify(exactly = 0) { manager.ensureLoaded() }
    }

    @Test fun unpublishedOrDisabledSnapshotsHaveNoContributionsAndNeverForceLoad() {
        snapshots.value = PluginSnapshot()
        assertTrue(source.skills().isEmpty())
        snapshots.value = PluginSnapshot(10, listOf(installed.copy(status = PluginStatus.DISABLED)))
        assertTrue(source.agents().isEmpty())
        assertEquals(0, checks)
        verify(exactly = 0) { manager.skills(); manager.agents(); manager.capture(any()) }
        coVerify(exactly = 0) { manager.ensureLoaded() }
    }

    @Test fun untrustedPublishedPluginsAreNotTreatedAsEnabled() {
        snapshots.value = PluginSnapshot(10, listOf(installed.copy(record = installed.record.copy(trustHash = null))))
        assertTrue(source.skills().isEmpty())
        assertTrue(source.agents().isEmpty())
        assertEquals(0, checks)
    }

    @Test fun mismatchedContributionEpochIsRejectedBeforeHashingOrRecapturingById() {
        snapshots.value = PluginSnapshot(10, listOf(installed.copy(record = installed.record.copy(activationEpoch = 8))))
        val failure = assertThrows(PluginCaptureException::class.java) { source.skills() }
        assertEquals(PluginErrorCode.STALE_CAPTURE, failure.error.code)
        assertEquals(0, checks)
        verify(exactly = 0) { manager.capture(any()) }
    }

    @Test fun cancellationFromTheOriginalWrapperIsPropagatedWithoutFallback() {
        val cancelled = CancellationException("cancelled hash")
        validation = { throw cancelled }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { source.skills() })
        assertSame(cancelled, assertThrows(CancellationException::class.java) { source.agents() })
        verify(exactly = 0) { manager.capture(any()) }
    }

    @Test fun sourceChangesDuringContributionCaptureRejectTheLateList() {
        every { manager.skills() } answers {
            snapshots.value = PluginSnapshot(10, listOf(installed.copy(record = installed.record.copy(activationEpoch = 8))))
            skillContributions
        }
        assertThrows(CancellationException::class.java) { source.skills() }
        assertEquals(0, checks)
        verify(exactly = 0) { manager.capture(any()) }
    }

    @Test fun sourceChangesDuringLastFullHashRejectTheResultEvenWhenTheValidatorReturns() {
        validation = {
            if (checks == 2) snapshots.value = PluginSnapshot(10,
                listOf(installed.copy(record = installed.record.copy(activationEpoch = 8))))
        }
        assertThrows(CancellationException::class.java) { source.agents() }
        assertEquals(2, checks)
        verify(exactly = 0) { manager.capture(any()) }
    }

    @Test fun failureOnTheSecondFullHashDoesNotReturnThePreviouslyPublishedBody() {
        validation = {
            if (checks == 2) throw PluginCaptureException(PluginError(PluginErrorCode.CONTENT_CHANGED))
        }
        val failure = assertThrows(PluginCaptureException::class.java) { source.skills() }
        assertEquals(PluginErrorCode.CONTENT_CHANGED, failure.error.code)
        assertEquals(2, checks)
    }

    @Test fun divergentRevisionAndSnapshotAreRejectedBeforeReadingAnyContribution() {
        every { manager.revision } returns MutableStateFlow(8L)
        assertThrows(CancellationException::class.java) { source.skills() }
        assertThrows(CancellationException::class.java) { source.agents() }
        assertEquals(0, checks)
        verify(exactly = 0) { manager.skills(); manager.agents(); manager.capture(any()) }
    }
}
