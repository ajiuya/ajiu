package com.aicode.feature.backup.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMetadataTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldMessageBackupsKeepModelInputSnapshotUnknown() {
        for (fields in listOf("", ",\"modelInputSnapshotJson\":null")) {
            val dto = json.decodeFromString<AgentMessageDto>(
                """{"id":"message","sessionId":"session","role":"USER","content":"Visible text","timestamp":1$fields}"""
            )
            assertEquals("Visible text", dto.content)
            assertNull(dto.modelInputSnapshotJson)
        }
        val snapshot = json.decodeFromString<BackupSnapshot>("""{"schemaVersion":69,"createdAt":1}""")
        assertNull(snapshot.projectJournalHistoryJson)
    }

    @Test
    fun modelInputSnapshotAndProjectHistoryRoundtripWithoutExecutionAuthorization() {
        val snapshot = BackupSnapshot(
            schemaVersion = 70, createdAt = 1,
            chatSessions = listOf(ChatSessionDto("session", "Title", 1, 1)),
            agentMessages = listOf(AgentMessageDto(
                "message", "session", "USER", "Visible text", 1,
                modelInputSnapshotJson = """{"version":1,"text":"Frozen model input"}"""
            )),
            projectJournalHistoryJson = """{"schemaVersion":1,"projects":[],"entries":[],"archives":[]}"""
        )
        val encoded = json.encodeToString(BackupSnapshot.serializer(), snapshot)
        val restored = json.decodeFromString<BackupSnapshot>(encoded)

        assertEquals(snapshot, restored)
        assertEquals("Visible text", restored.agentMessages.single().content)
        assertFalse(encoded.contains("executionGeneration"))
        assertFalse(encoded.contains("autoDispatchBlockedReason"))
        assertFalse(encoded.contains("recoveryRequests"))
        assertFalse(encoded.contains("pendingApprovals"))
        assertFalse(json.encodeToString(BackupMetadata.serializer(), snapshot.toMetadata()).contains("projectJournalHistoryJson"))
    }

    @Test
    fun chatBackupDoesNotIncludeAppSettings() {
        val metadata = BackupMetadata(schemaVersion = 55, createdAt = 0)
        val restored = json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata))
        assertFalse(restored.includesAppSettings)
    }

    @Test
    fun legacyMetadataInfersSettingsFromThemeMode() {
        assertTrue(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":"DARK"}"""
        ).includesAppSettings)
        assertFalse(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":null}"""
        ).includesAppSettings)
    }

    @Test
    fun explicitSettingsScopeOverridesLegacyInference() {
        assertFalse(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":"DARK","includesAppSettings":false}"""
        ).includesAppSettings)
    }

    @Test
    fun legacySnapshotPreservesSettingsScope() {
        assertFalse(BackupSnapshot(schemaVersion = 55, createdAt = 0).toMetadata().includesAppSettings)
        assertTrue(BackupSnapshot(schemaVersion = 55, createdAt = 0, themeMode = "AUTO").toMetadata().includesAppSettings)
    }

    @Test
    fun legacyTypewriterSettingRemainsUnknownInBothFormats() {
        for (fields in listOf("", ",\"typewriterCharsPerSecond\":null")) {
            val encoded = """{"schemaVersion":65,"createdAt":0,"themeMode":"DARK"$fields}"""
            val metadata = json.decodeFromString<BackupMetadata>(encoded)
            val snapshot = json.decodeFromString<BackupSnapshot>(encoded)

            assertNull(metadata.typewriterCharsPerSecond)
            assertNull(snapshot.typewriterCharsPerSecond)
            assertNull(snapshot.toMetadata().typewriterCharsPerSecond)
        }
    }

    @Test
    fun typewriterSettingRoundtripMapsToMetadata() {
        for (speed in listOf(null, 10, 30, 120, 350)) {
            val snapshot = BackupSnapshot(
                schemaVersion = 66, createdAt = 123, themeMode = "DARK", typewriterCharsPerSecond = speed
            )
            val restored = json.decodeFromString<BackupSnapshot>(json.encodeToString(BackupSnapshot.serializer(), snapshot))
            assertEquals(snapshot, restored)
            val metadata = restored.toMetadata()
            val restoredMetadata = json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata))

            assertTrue(restoredMetadata.includesAppSettings)
            assertEquals(speed, restoredMetadata.typewriterCharsPerSecond)
            assertEquals(metadata, restoredMetadata)
        }
    }

    @Test
    fun oldBackupsDoNotInventAReasoningPreference() {
        for (fields in listOf("", ",\"autoExpandReasoning\":null")) {
            val content = """{"schemaVersion":71,"createdAt":0,"themeMode":"DARK"$fields}"""
            assertNull(json.decodeFromString<BackupMetadata>(content).autoExpandReasoning)
            val snapshot = json.decodeFromString<BackupSnapshot>(content)
            assertNull(snapshot.autoExpandReasoning)
            assertNull(snapshot.toMetadata().autoExpandReasoning)
        }
    }

    @Test
    fun reasoningPreferenceRoundtripPreservesNullFalseAndTrue() {
        for (enabled in listOf(null, false, true)) {
            val snapshot = BackupSnapshot(schemaVersion = 71, createdAt = 1,
                themeMode = "DARK", autoExpandReasoning = enabled)
            val restored = json.decodeFromString<BackupSnapshot>(json.encodeToString(BackupSnapshot.serializer(), snapshot))
            assertEquals(enabled, restored.autoExpandReasoning)
            val metadata = restored.toMetadata()
            assertEquals(enabled, metadata.autoExpandReasoning)
            assertEquals(metadata, json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata)))
        }
    }

    @Test
    fun oldBubbleStyleSettingsRemainNullInBothBackupFormats() {
        for (fields in listOf("", ",\"userChatBubbleStyleId\":null,\"assistantChatBubbleStyleId\":null")) {
            val encoded = """{"schemaVersion":67,"createdAt":0,"themeMode":"DARK"$fields}"""
            val metadata = json.decodeFromString<BackupMetadata>(encoded)
            val snapshot = json.decodeFromString<BackupSnapshot>(encoded)

            assertNull(metadata.userChatBubbleStyleId)
            assertNull(metadata.assistantChatBubbleStyleId)
            assertNull(snapshot.userChatBubbleStyleId)
            assertNull(snapshot.assistantChatBubbleStyleId)
            assertNull(snapshot.toMetadata().userChatBubbleStyleId)
            assertNull(snapshot.toMetadata().assistantChatBubbleStyleId)
        }
    }

    @Test
    fun bubbleStyleSettingsRoundtripAndMapToMetadataIndependently() {
        for ((userId, assistantId) in listOf("shadow" to "document", null to null, "classic" to null, null to "minimal")) {
            val snapshot = BackupSnapshot(
                schemaVersion = 68, createdAt = 123, themeMode = "DARK",
                userChatBubbleStyleId = userId, assistantChatBubbleStyleId = assistantId
            )
            val restored = json.decodeFromString<BackupSnapshot>(json.encodeToString(BackupSnapshot.serializer(), snapshot))
            assertEquals(snapshot, restored)
            val metadata = restored.toMetadata()
            val restoredMetadata = json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata))

            assertTrue(restoredMetadata.includesAppSettings)
            assertEquals(userId, restoredMetadata.userChatBubbleStyleId)
            assertEquals(assistantId, restoredMetadata.assistantChatBubbleStyleId)
            assertEquals(metadata, restoredMetadata)
        }
    }

    @Test
    fun legacyAppearanceFieldsRemainUnknown() {
        for (fields in listOf("", ",\"chatTextScale\":null,\"chatBackgroundAlpha\":null")) {
            val encoded = """{"schemaVersion":64,"createdAt":0,"themeMode":"DARK"$fields}"""
            val metadata = json.decodeFromString<BackupMetadata>(encoded)
            val snapshot = json.decodeFromString<BackupSnapshot>(encoded)
            assertNull(metadata.chatTextScale)
            assertNull(metadata.chatBackgroundAlpha)
            assertNull(snapshot.chatTextScale)
            assertNull(snapshot.chatBackgroundAlpha)
            assertNull(snapshot.toMetadata().chatTextScale)
            assertNull(snapshot.toMetadata().chatBackgroundAlpha)
        }
    }

    @Test
    fun appearanceMetadataRoundtripPreservesValuesAndNulls() {
        for ((scale, alpha) in listOf(1.25f to 0.4f, null to null, 0.9f to null, null to 0f)) {
            val metadata = BackupMetadata(
                schemaVersion = 65,
                createdAt = 123,
                includesAppSettings = true,
                chatTextScale = scale,
                chatBackgroundAlpha = alpha
            )
            val restored = json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata))
            assertEquals(metadata, restored)
        }
    }

    @Test
    fun snapshotRoundtripMapsAppearanceToMetadata() {
        val snapshot = BackupSnapshot(
            schemaVersion = 65,
            createdAt = 123,
            themeMode = "DARK",
            chatTextScale = 1.25f,
            chatBackgroundAlpha = 0.4f
        )
        val restored = json.decodeFromString<BackupSnapshot>(json.encodeToString(BackupSnapshot.serializer(), snapshot))
        assertEquals(snapshot, restored)
        val metadata = restored.toMetadata()
        assertTrue(metadata.includesAppSettings)
        assertEquals(snapshot.chatTextScale, metadata.chatTextScale)
        assertEquals(snapshot.chatBackgroundAlpha, metadata.chatBackgroundAlpha)
    }

    @Test
    fun legacyMetadataDefaultsToEmptyVersionOneContent() {
        for (fields in listOf("", ",\"contentSections\":[]")) {
            val metadata = json.decodeFromString<BackupMetadata>(
                """{"schemaVersion":63,"createdAt":1$fields}"""
            )
            assertEquals(1, metadata.contentFormatVersion)
            assertTrue(metadata.contentSections.isEmpty())
            assertTrue(metadata.pluginSections.isEmpty())
        }
        val legacy = BackupSnapshot(schemaVersion = 63, createdAt = 1).toMetadata()
        assertEquals(1, legacy.contentFormatVersion)
        assertTrue(legacy.contentSections.isEmpty())
    }

    @Test
    fun backupOptionsPreserveOriginalDefaultsAndKeepContentOptIn() {
        val options = BackupOptions()
        assertTrue(options.providers)
        assertTrue(options.remoteConnections)
        assertTrue(options.chatHistory)
        assertTrue(options.mcpServers)
        assertTrue(options.permissionRules)
        assertTrue(options.appSettings)
        assertFalse(options.workspaceFiles)
        assertTrue(options.pluginIds.isEmpty())
        assertFalse(options.memories)
        assertFalse(options.skills)
        assertFalse(options.prompts)
        assertFalse(options.plugins)
    }

    @Test
    fun contentSectionRoundtripPreservesEveryScopeAndSourceIdentity() {
        val sections = BackupContentKind.entries.flatMap { kind ->
            BackupContentLocation.entries.filter { location ->
                when (kind) {
                    BackupContentKind.PLUGIN -> location == BackupContentLocation.PLUGIN
                    BackupContentKind.SKILLS -> location != BackupContentLocation.PLUGIN
                    BackupContentKind.MEMORY, BackupContentKind.PROMPTS -> location in setOf(
                        BackupContentLocation.PHONE_GLOBAL, BackupContentLocation.PHONE_PROJECT,
                        BackupContentLocation.PHONE_REMOTE_PROJECT
                    )
                }
            }.map { location ->
                BackupContentSectionMeta(
                    id = "${kind.name}_${location.name}", kind = kind, location = location,
                    label = "中文分区 ${kind.name} ${location.name}", fileCount = 7, bytes = 1234,
                    workspaceName = "工程", sourceKey = "source-profile-1", sourceLabel = "手机或当前 SSH",
                    pluginId = if (kind == BackupContentKind.PLUGIN) "fixture.full" else null,
                    pluginVersion = if (kind == BackupContentKind.PLUGIN) "1.2.3" else null,
                    pluginContentHash = if (kind == BackupContentKind.PLUGIN) "a".repeat(64) else null
                )
            }
        }
        val metadata = BackupMetadata(schemaVersion = 63, createdAt = 1, contentSections = sections)
        val restored = json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata))

        assertEquals(metadata, restored)
        assertEquals(12, restored.contentSections.size)
        sections.zip(restored.contentSections).forEach { (expected, actual) ->
            assertEquals(expected.kind, actual.kind)
            assertEquals(expected.location, actual.location)
            assertEquals(expected.label, actual.label)
            assertEquals(expected.workspaceName, actual.workspaceName)
            assertEquals(expected.sourceKey, actual.sourceKey)
            assertEquals(expected.sourceLabel, actual.sourceLabel)
            assertEquals(expected.pluginId, actual.pluginId)
            assertEquals(expected.pluginVersion, actual.pluginVersion)
            assertEquals(expected.pluginContentHash, actual.pluginContentHash)
        }
    }

    @Test
    fun contentMetadataDoesNotSerializeRuntimeAuthorization() {
        val metadata = BackupMetadata(schemaVersion = 63, createdAt = 1, contentSections = listOf(
            BackupContentSectionMeta(
                "plugin", BackupContentKind.PLUGIN, BackupContentLocation.PLUGIN, "完整插件",
                pluginId = "fixture.full", pluginVersion = "1.0.0", pluginContentHash = "b".repeat(64)
            )
        ))
        val encoded = json.encodeToString(BackupMetadata.serializer(), metadata)

        for (field in listOf("root", "capabilities", "trust", "trustGrant", "trustGrants", "operationGrants", "pendingApprovals", "executionGeneration")) {
            assertFalse(field, encoded.contains("\"$field\""))
        }
        assertEquals(metadata, json.decodeFromString<BackupMetadata>(encoded))
    }

    @Test
    fun contentSelectionDefaultsNeverSelectOrOverwriteAnything() {
        val selection = BackupContentRestoreSelection()
        assertTrue(selection.sectionIds.isEmpty())
        assertFalse(selection.overwrite)
        assertNull(selection.remoteTargetIdentity)
        assertEquals(selection, BackupContentRestoreSelection(emptySet(), false, null))
    }

    @Test
    fun importPreviewKeepsContentAndRemoteTargetSeparateFromWorkspaceFlag() {
        val section = BackupContentSectionMeta(
            "ssh-skills", BackupContentKind.SKILLS, BackupContentLocation.SSH_PROJECT, "SSH 项目技能",
            workspaceName = "project", sourceKey = "profile-1", sourceLabel = "Current SSH"
        )
        val target = BackupContentRestoreTarget("target-identity", "当前 SSH")
        val preview = ImportPreview(emptyList(), contentSections = listOf(section), remoteTarget = target)
        assertFalse(preview.hasWorkspaceData)
        assertEquals(listOf(section), preview.contentSections)
        assertEquals(target, preview.remoteTarget)
        assertTrue(preview.pluginSections.isEmpty())

        val legacy = ImportPreview(listOf(WorkspaceBackupMeta("project", 2)))
        assertTrue(legacy.hasWorkspaceData)
        assertTrue(legacy.contentSections.isEmpty())
        assertNull(legacy.remoteTarget)
    }

    @Test
    fun restoreStatsPlusPreservesEveryCounterAndBothResultLists() {
        val first = BackupContentRestoreResult(
            "memory", BackupContentKind.MEMORY, "记忆", BackupContentRestoreStatus.RESTORED,
            restoredFiles = 2, skippedFiles = 3, conflicts = 1, code = "MEMORY_RESULT"
        )
        val second = BackupContentRestoreResult(
            "skills", BackupContentKind.SKILLS, "技能", BackupContentRestoreStatus.NOT_SELECTED
        )
        val hook = PluginBackupSectionResult("fixture.full", PluginBackupSectionStatus.UNAVAILABLE, "FULL_PLUGIN_RESTORE_SELECTED")
        val left = RestoreStats(1, 2, 3, 4, 5, 6, 7, 8, 9, listOf(hook), listOf(first))
        val right = RestoreStats(9, 8, 7, 6, 5, 4, 3, 2, 1, contentSections = listOf(second))
        val sum = left + right

        assertEquals(10, sum.providers)
        assertEquals(10, sum.remoteConnections)
        assertEquals(10, sum.remoteMounts)
        assertEquals(10, sum.chatSessions)
        assertEquals(10, sum.agentMessages)
        assertEquals(10, sum.todoItems)
        assertEquals(10, sum.mcpServers)
        assertEquals(10, sum.globalPermissionRules)
        assertEquals(10, sum.workspaceFiles)
        assertEquals(listOf(hook), sum.pluginSections)
        assertEquals(listOf(first, second), sum.contentSections)
        assertEquals(left, left + RestoreStats())
        assertEquals(right, RestoreStats() + right)
        assertTrue(RestoreStats().contentSections.isEmpty())
    }
}
