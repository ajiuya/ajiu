package com.aicode.feature.backup.presentation

import android.content.Context
import com.aicode.R
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreResult
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.backup.domain.BackupContentRestoreTarget
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import com.aicode.feature.backup.domain.PluginBackupSectionResult
import com.aicode.feature.backup.domain.PluginBackupSectionStatus
import com.aicode.feature.backup.domain.RestoreStats
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupContentPresentationTest {
    private val target = BackupContentRestoreTarget("opaque-target", "Current SSH")
    private val phone = section("phone", BackupContentLocation.PHONE_GLOBAL)
    private val ssh = section("ssh", BackupContentLocation.SSH_PROJECT, BackupContentKind.SKILLS)
    private val plugin = section("plugin", BackupContentLocation.PLUGIN, BackupContentKind.PLUGIN).copy(pluginId = "pet")

    @Test
    fun emptySelectionCanRestoreCoreSettings() {
        val choices = BackupRestoreChoices()
        assertTrue(choices.canConfirm(emptyList(), null))
        assertEquals(BackupContentRestoreSelection(), choices.contentSelection(emptyList(), null))
    }

    @Test
    fun deselectingEveryWorkspaceDoesNotDisableCoreRestore() {
        val choices = BackupRestoreChoices(workspaces = setOf("project")).copy(workspaces = emptySet())
        assertTrue(choices.canConfirm(listOf(phone, ssh, plugin), target))
    }

    @Test
    fun contentAndExtensionsDefaultToUnselectedWithoutOverwriteOrRemoteConsent() {
        val choices = BackupRestoreChoices(workspaces = setOf("project"))
        assertTrue(choices.content.sectionIds.isEmpty())
        assertTrue(choices.extensions.isEmpty())
        assertFalse(choices.content.overwrite)
        assertFalse(choices.remoteConfirmed)
        assertNull(choices.content.remoteTargetIdentity)
    }

    @Test
    fun allPhoneLocationsIncludingCachedSshProjectsNeedNoRemoteConsent() {
        val locations = listOf(BackupContentLocation.PHONE_GLOBAL, BackupContentLocation.PHONE_PROJECT, BackupContentLocation.PHONE_REMOTE_PROJECT)
        for (location in locations) {
            for (kind in listOf(BackupContentKind.MEMORY, BackupContentKind.SKILLS, BackupContentKind.PROMPTS)) {
                val section = section("phone-$location-$kind", location, kind)
                val choices = BackupRestoreChoices().selectContent(section, true)
                assertFalse(choices.requiresRemoteConfirmation(listOf(section)))
                assertTrue(choices.canConfirm(listOf(section), null))
                assertNull(choices.contentSelection(listOf(section), target).remoteTargetIdentity)
            }
        }
    }

    @Test
    fun bothSshLocationsRequireExplicitConfirmation() {
        for (location in listOf(BackupContentLocation.SSH_GLOBAL, BackupContentLocation.SSH_PROJECT)) {
            val section = ssh.copy(location = location)
            val choices = BackupRestoreChoices().selectContent(section, true)
            assertTrue(choices.requiresRemoteConfirmation(listOf(section)))
            assertFalse(choices.canConfirm(listOf(section), target))
            assertNull(choices.contentSelection(listOf(section), target).remoteTargetIdentity)
            val confirmed = choices.copy(remoteConfirmed = true)
            assertTrue(confirmed.canConfirm(listOf(section), target))
            assertEquals(target.identity, confirmed.contentSelection(listOf(section), target).remoteTargetIdentity)
        }
    }

    @Test
    fun confirmationCannotInventAnUnavailableRemoteTarget() {
        val choices = BackupRestoreChoices(remoteConfirmed = true).selectContent(ssh, true)
        assertFalse(choices.canConfirm(listOf(ssh), null))
        assertNull(choices.contentSelection(listOf(ssh), null).remoteTargetIdentity)
    }

    @Test
    fun deselectingSshContentAllowsCoreRestoreAndDropsTheRemoteIdentity() {
        val choices = BackupRestoreChoices(remoteConfirmed = true).selectContent(ssh, true).selectContent(ssh, false)
        assertTrue(choices.canConfirm(listOf(ssh), null))
        assertNull(choices.contentSelection(listOf(ssh), target).remoteTargetIdentity)
    }

    @Test
    fun selectingAFullPluginRemovesOnlyItsOwnExtension() {
        val choices = BackupRestoreChoices(extensions = setOf("pet", "other")).selectContent(plugin, true)
        assertEquals(setOf("other"), choices.extensions)
        assertEquals(setOf(plugin.id), choices.content.sectionIds)
        assertEquals(setOf("pet"), choices.fullPluginIds(listOf(plugin)))
    }

    @Test
    fun theReplacedExtensionCannotBeReselectedWhileFullRestoreIsSelected() {
        val choices = BackupRestoreChoices().selectContent(plugin, true)
        assertEquals(choices, choices.selectExtension("pet", true, listOf(plugin)))
        assertEquals(setOf("other"), choices.selectExtension("other", true, listOf(plugin)).extensions)
    }

    @Test
    fun deselectingFullRestoreDoesNotAutomaticallySelectItsHook() {
        val choices = BackupRestoreChoices(extensions = setOf("pet"))
            .selectContent(plugin, true).selectContent(plugin, false)
        assertTrue(choices.extensions.isEmpty())
        assertTrue(choices.fullPluginIds(listOf(plugin)).isEmpty())
        assertEquals(setOf("pet"), choices.selectExtension("pet", true, listOf(plugin)).extensions)
    }

    @Test
    fun selectingPhoneContentDoesNotRemoveIndependentExtensions() {
        val choices = BackupRestoreChoices(extensions = setOf("pet")).selectContent(phone, true)
        assertEquals(setOf("pet"), choices.extensions)
        assertTrue(choices.fullPluginIds(listOf(phone)).isEmpty())
    }

    @Test
    fun overwriteRequiresItsOwnOptInAndPreservesTheChosenSections() {
        val choices = BackupRestoreChoices().selectContent(phone, true)
        assertFalse(choices.contentSelection(listOf(phone), null).overwrite)
        val optedIn = choices.copy(content = choices.content.copy(overwrite = true))
        assertTrue(optedIn.contentSelection(listOf(phone), null).overwrite)
        assertEquals(setOf(phone.id), optedIn.contentSelection(listOf(phone), null).sectionIds)
    }

    @Test
    fun everyKindUsesItsAgreedResource() {
        assertEquals(R.string.backup_data_memories, backupContentKindLabel(BackupContentKind.MEMORY))
        assertEquals(R.string.backup_data_skills, backupContentKindLabel(BackupContentKind.SKILLS))
        assertEquals(R.string.backup_data_prompts, backupContentKindLabel(BackupContentKind.PROMPTS))
        assertEquals(R.string.backup_data_plugins, backupContentKindLabel(BackupContentKind.PLUGIN))
    }

    @Test
    fun phoneRemoteProjectHasADifferentLabelFromAnSshWriteTarget() {
        assertEquals(R.string.backup_restore_phone_global, backupContentLocationLabel(BackupContentLocation.PHONE_GLOBAL))
        assertEquals(R.string.backup_restore_phone_project, backupContentLocationLabel(BackupContentLocation.PHONE_PROJECT))
        assertEquals(R.string.backup_restore_phone_remote_project, backupContentLocationLabel(BackupContentLocation.PHONE_REMOTE_PROJECT))
        assertEquals(R.string.backup_restore_ssh_global, backupContentLocationLabel(BackupContentLocation.SSH_GLOBAL))
        assertEquals(R.string.backup_restore_ssh_project, backupContentLocationLabel(BackupContentLocation.SSH_PROJECT))
        assertEquals(R.string.backup_data_plugins, backupContentLocationLabel(BackupContentLocation.PLUGIN))
    }

    @Test
    fun summaryIncludesEveryKindStatusAndAllFileCounts() {
        val context = summaryContext()
        val results = BackupContentKind.entries.flatMap { kind ->
            BackupContentRestoreStatus.entries.map { status ->
                BackupContentRestoreResult("$kind-$status", kind, "$kind/$status", status, 2, 3, 4)
            }
        }
        val summary = buildImportSummary(context, RestoreStats(contentSections = results))
        val statusResources = listOf(
            R.string.backup_content_restored, R.string.backup_content_not_selected,
            R.string.backup_content_conflict, R.string.backup_content_unavailable,
            R.string.backup_content_partial, R.string.backup_content_failed
        )
        results.forEach { result ->
            val status = "resource:${statusResources[result.status.ordinal]}"
            assertTrue(summary.contains("${result.label}|$status|2|3|4"))
        }
        BackupContentKind.entries.forEach { kind -> assertTrue(summary.contains("resource:${backupContentKindLabel(kind)}")) }
    }

    @Test
    fun partialConflictingUnavailableAndFailedContentAreNotAllSuccessful() {
        for (status in BackupContentRestoreStatus.entries) {
            val stats = RestoreStats(contentSections = listOf(BackupContentRestoreResult("id", BackupContentKind.MEMORY, "Memory", status)))
            assertEquals(status != BackupContentRestoreStatus.RESTORED && status != BackupContentRestoreStatus.NOT_SELECTED, stats.hasContentRestoreIssues())
        }
        assertFalse(RestoreStats().hasContentRestoreIssues())
    }

    @Test
    fun oldExtensionResultsRemainVisibleAndFailuresRemainNonSuccessful() {
        val context = summaryContext()
        val stats = RestoreStats(
            providers = 1,
            pluginSections = listOf(PluginBackupSectionResult("legacy", PluginBackupSectionStatus.DENIED))
        )
        val summary = buildImportSummary(context, stats)
        assertTrue(summary.contains("resource:${R.string.backup_stat_providers}|1"))
        assertTrue(summary.contains("resource:${R.string.plugin_backup_result}|legacy|resource:${R.string.plugin_backup_denied}"))
        assertTrue(stats.hasContentRestoreIssues())
        assertFalse(RestoreStats(pluginSections = listOf(PluginBackupSectionResult("legacy", PluginBackupSectionStatus.NOT_SELECTED))).hasContentRestoreIssues())
    }

    @Test
    fun installedPluginsWithFailedOrInvalidDataExplainThatPrivateDataWasNotRestored() {
        val context = summaryContext()
        for (code in listOf("PLUGIN_PACKAGE_RESTORED_DATA_FAILED", "PLUGIN_PACKAGE_RESTORED_DATA_INVALID")) {
            val result = BackupContentRestoreResult(
                "full-pet", BackupContentKind.PLUGIN, "Pet", BackupContentRestoreStatus.PARTIAL,
                restoredFiles = 1, skippedFiles = 2, conflicts = 0, code = code
            )
            val stats = RestoreStats(contentSections = listOf(result))
            val summary = buildImportSummary(context, stats)
            assertTrue(summary.contains("Pet|resource:${R.string.backup_content_partial}|1|2|0"))
            assertTrue(summary.contains("resource:${R.string.backup_content_plugin_package_data_failed}"))
            assertFalse(summary.contains("resource:${R.string.backup_content_plugin_secrets_failed}"))
            assertFalse(summary.contains("resource:${R.string.backup_content_plugin_restore_blocked}"))
            assertFalse(summary.contains(code))
            assertTrue(stats.hasContentRestoreIssues())
        }
    }

    @Test
    fun restoredPackageAndDataWithFailedSecretsHaveTheirOwnExplanation() {
        val result = BackupContentRestoreResult(
            "full-pet", BackupContentKind.PLUGIN, "Pet", BackupContentRestoreStatus.PARTIAL,
            restoredFiles = 3, skippedFiles = 1, conflicts = 0, code = "PLUGIN_PACKAGE_DATA_RESTORED_SECRETS_FAILED"
        )
        val stats = RestoreStats(contentSections = listOf(result))
        val summary = buildImportSummary(summaryContext(), stats)
        assertTrue(summary.contains("Pet|resource:${R.string.backup_content_partial}|3|1|0"))
        assertTrue(summary.contains("resource:${R.string.backup_content_plugin_secrets_failed}"))
        assertFalse(summary.contains("resource:${R.string.backup_content_plugin_package_data_failed}"))
        assertFalse(summary.contains("resource:${R.string.backup_content_plugin_restore_blocked}"))
        assertFalse(summary.contains(requireNotNull(result.code)))
        assertTrue(stats.hasContentRestoreIssues())
    }

    @Test
    fun retainedOldPluginDataExplainsThatTheUserMustReviewTheControlledBackup() {
        val result = BackupContentRestoreResult(
            "full-pet", BackupContentKind.PLUGIN, "Pet", BackupContentRestoreStatus.PARTIAL,
            restoredFiles = 1, skippedFiles = 2, conflicts = 1, code = "PLUGIN_PACKAGE_RESTORED_OLD_DATA_RETAINED"
        )
        val stats = RestoreStats(contentSections = listOf(result))
        val summary = buildImportSummary(summaryContext(), stats)
        assertTrue(summary.contains("Pet|resource:${R.string.backup_content_partial}|1|2|1"))
        assertTrue(summary.contains("resource:${R.string.backup_content_plugin_old_data_retained}"))
        assertFalse(summary.contains("resource:${R.string.backup_content_plugin_package_data_failed}"))
        assertFalse(summary.contains("resource:${R.string.backup_content_plugin_restore_blocked}"))
        assertFalse(summary.contains(requireNotNull(result.code)))
        assertTrue(stats.hasContentRestoreIssues())
    }

    @Test
    fun genericPluginFailuresExplainTheBlockWithoutMislabelingOtherKindsOrStatuses() {
        val context = summaryContext()
        val blocked = "resource:${R.string.backup_content_plugin_restore_blocked}"
        for (code in listOf(null, "PLUGIN_RUNTIME_BUSY", "PLUGIN_PACKAGE_INVALID", "PLUGIN_REGISTRY_UNAVAILABLE", "UNKNOWN_FAILURE")) {
            val result = BackupContentRestoreResult(
                "full-pet", BackupContentKind.PLUGIN, "Pet", BackupContentRestoreStatus.FAILED, code = code
            )
            val summary = buildImportSummary(context, RestoreStats(contentSections = listOf(result)))
            assertTrue(summary.contains("Pet|resource:${R.string.backup_content_failed}|0|0|0"))
            assertTrue(summary.contains(blocked))
            if (code != null) assertFalse(summary.contains(code))
        }
        for (kind in BackupContentKind.entries.filter { it != BackupContentKind.PLUGIN }) {
            val result = BackupContentRestoreResult("id", kind, "Content", BackupContentRestoreStatus.FAILED, code = "PLUGIN_RUNTIME_BUSY")
            assertFalse(buildImportSummary(context, RestoreStats(contentSections = listOf(result))).contains(blocked))
        }
        for (status in BackupContentRestoreStatus.entries.filter { it != BackupContentRestoreStatus.FAILED }) {
            val result = BackupContentRestoreResult("full-pet", BackupContentKind.PLUGIN, "Pet", status, code = "UNKNOWN_STATUS")
            assertFalse(buildImportSummary(context, RestoreStats(contentSections = listOf(result))).contains(blocked))
        }
    }

    @Test
    fun extensionsSkippedForFullPluginRestoreExplainTheMutualExclusion() {
        val stats = RestoreStats(pluginSections = listOf(
            PluginBackupSectionResult("pet", PluginBackupSectionStatus.UNAVAILABLE, "FULL_PLUGIN_RESTORE_SELECTED"),
            PluginBackupSectionResult("other", PluginBackupSectionStatus.UNAVAILABLE)
        ))
        val summary = buildImportSummary(summaryContext(), stats)
        assertTrue(summary.contains("resource:${R.string.plugin_backup_result}|pet|resource:${R.string.backup_restore_extension_replaced}"))
        assertTrue(summary.contains("resource:${R.string.plugin_backup_result}|other|resource:${R.string.plugin_backup_unavailable}"))
        assertFalse(summary.contains("resource:${R.string.plugin_backup_result}|pet|resource:${R.string.plugin_backup_unavailable}"))
        assertFalse(summary.contains("FULL_PLUGIN_RESTORE_SELECTED"))
    }

    private fun summaryContext(): Context = mockk<Context> {
        every { getString(any()) } answers { "resource:${firstArg<Int>()}" }
        every { getString(any(), *anyVararg()) } answers {
            val resource = firstArg<Int>()
            val arguments = secondArg<Array<out Any>>()
            if (resource == R.string.backup_content_summary) arguments.joinToString("|")
            else "resource:$resource|${arguments.joinToString("|")}"
        }
    }

    private fun section(id: String, location: BackupContentLocation, kind: BackupContentKind = BackupContentKind.MEMORY) =
        BackupContentSectionMeta(id, kind, location, id, fileCount = 2, bytes = 1024)
}
