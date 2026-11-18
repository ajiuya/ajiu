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
}
