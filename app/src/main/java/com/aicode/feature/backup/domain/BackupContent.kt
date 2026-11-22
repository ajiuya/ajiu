package com.aicode.feature.backup.domain

import kotlinx.serialization.Serializable

@Serializable
enum class BackupContentKind { MEMORY, SKILLS, PROMPTS, PLUGIN }

@Serializable
enum class BackupContentLocation { PHONE_GLOBAL, PHONE_PROJECT, PHONE_REMOTE_PROJECT, SSH_GLOBAL, SSH_PROJECT, PLUGIN }

@Serializable
data class BackupContentSectionMeta(
    val id: String,
    val kind: BackupContentKind,
    val location: BackupContentLocation,
    val label: String,
    val fileCount: Int = 0,
    val bytes: Long = 0,
    val workspaceName: String? = null,
    val sourceKey: String? = null,
    val sourceLabel: String? = null,
    val pluginId: String? = null,
    val pluginVersion: String? = null,
    val pluginContentHash: String? = null
)

data class BackupContentRestoreSelection(
    val sectionIds: Set<String> = emptySet(),
    val overwrite: Boolean = false,
    val remoteTargetIdentity: String? = null
)

enum class BackupContentRestoreStatus { RESTORED, NOT_SELECTED, CONFLICT, UNAVAILABLE, PARTIAL, FAILED }

data class BackupContentRestoreResult(
    val sectionId: String,
    val kind: BackupContentKind,
    val label: String,
    val status: BackupContentRestoreStatus,
    val restoredFiles: Int = 0,
    val skippedFiles: Int = 0,
    val conflicts: Int = 0,
    val code: String? = null
)

data class BackupContentRestoreTarget(val identity: String, val label: String)
