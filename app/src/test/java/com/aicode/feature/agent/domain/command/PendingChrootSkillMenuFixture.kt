package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillDefinitionSourceChanged
import com.aicode.feature.agent.domain.skill.SkillEntry
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

internal class PendingChrootSkillMenuFixture(
    val mode: ExecutionModeHolder = ExecutionModeHolder(),
    val settings: ContainerSettingsRepository = mockk()
) {
    val profile = ContainerProfile.BUILTIN_ALPINE.copy(
        id = "pending-menu-chroot",
        name = "Pending menu CHROOT",
        isBuiltin = false,
        localRuntime = LocalContainerRuntime.CHROOT,
        chrootInstance = null
    )
    private val configurationRevisions = MutableStateFlow(0L)
    var profileSnapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 0L, 0L, settings)
        private set
    val localFolders = mockk<LocalFolderGrantRepository>()
    val runtimeCommands = mockk<LocalRuntimeCommands>()
    val fileAccess = mockk<FileAccessProvider>()
    val localFileAccess = mockk<LocalFileAccess>()
    val remoteFileAccess = mockk<RemoteSftpFileAccess>()
    val skillRepository = mockk<SkillRepository>()
    private val context = mockk<Context> {
        every { getString(R.string.slash_command_usage_desc) } returns "Fixture usage"
    }
    private data class Definitions(val identity: String, val skills: List<Skill>)
    private val definitions = AtomicReference(Definitions("fixture:startup", listOf(skill("host-skill"))))
    private val usage = object : ActionCommandHandler {
        override val name = "usage"
        override val descriptionRes = R.string.slash_command_usage_desc
        override val acceptsArgs = false
        override fun execute(context: SlashCommandContext, args: String) = Unit
    }
    val factory = WorkspaceExecutionContextFactory(
        fileAccess, mode, localFileAccess, remoteFileAccess, settings, localFolders, runtimeCommands
    )
    val registry = SlashCommandRegistry(setOf(usage), skillRepository, context, factory)

    init {
        every { settings.activeProfileFlow } returns flowOf(profile)
        every { settings.activeProfileIdFlow } returns flowOf(profile.id)
        every { settings.activeProfileSelectionRevision } returns 0L
        every { settings.activeProfileSelectionRevisions } returns MutableStateFlow(0L)
        every { settings.profileConfigurationRevision } answers { configurationRevisions.value }
        every { settings.profileConfigurationRevisions } returns configurationRevisions
        coEvery { settings.captureProfileSnapshot() } coAnswers { profileSnapshot }
        every { settings.checkProfileSnapshot(any()) } answers {
            check(firstArg<ContainerProfileSnapshot>() === profileSnapshot)
        }
        coEvery { localFolders.awaitReady() } returns Unit
        every { localFolders.snapshot() } returns LocalFolderSnapshot.EMPTY
        every { skillRepository.sourceIdentity() } answers { definitions.get().identity }
        every { skillRepository.checkSourceIdentity(any()) } answers {
            if (firstArg<String>() != definitions.get().identity) {
                throw SkillDefinitionSourceChanged()
            }
        }
        coEvery { skillRepository.readDefinitionSnapshot(factory, any()) } coAnswers {
            val captured = definitions.get()
            SkillRepository.DefinitionSnapshot(
                captured.identity,
                (captured.skills + skill("disabled-fixture")).map { SkillEntry(it, SkillScope.GLOBAL) },
                setOf("disabled-fixture")
            )
        }
    }

    fun selectDefinitions(identity: String, skillName: String) {
        definitions.set(Definitions(identity, listOf(skill(skillName))))
    }

    fun changeProfileConfiguration(identity: String, skillName: String) {
        selectDefinitions(identity, skillName)
        val revision = configurationRevisions.value + 1L
        profileSnapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 0L, revision, settings)
        configurationRevisions.value = revision
    }

    fun assertPendingWithoutExecutionOwner() {
        assertEquals(ExecutionMode.LOCAL_PROOT, mode.currentMode())
        assertEquals(profile.id, profileSnapshot.selectedProfileId)
        assertEquals(profile, profileSnapshot.activeProfile)
        assertTrue(profileSnapshot.activeProfile.isChroot)
        assertNull(profileSnapshot.activeProfile.chrootInstance)
        assertNull(WorkspaceExecutionContext.current())
    }

    fun assertNoRuntimeOrFileAccessCalls() {
        verify {
            listOf(runtimeCommands, fileAccess, localFileAccess, remoteFileAccess) wasNot Called
        }
    }

    private fun skill(name: String) = Skill(
        name = name,
        description = "Fixture $name",
        instructions = "Fixture instructions for $name"
    )
}
