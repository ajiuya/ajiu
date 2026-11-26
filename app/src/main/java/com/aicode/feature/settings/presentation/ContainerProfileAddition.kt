package com.aicode.feature.settings.presentation

import com.aicode.feature.agent.domain.container.ContainerImageEntry
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.displayLabel
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.effectiveVersion
import com.aicode.feature.settings.presentation.component.buildContainerProfile
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** A one-shot request constructed by an explicit Add/Import click, never by observing stored profiles. */
internal data class ContainerProfileAddition(
    val profile: ContainerProfile,
    val setAsDefault: Boolean = false,
    /** The exact download selected by the user, not the latest catalog/download at execution time. */
    val downloadedImage: DownloadedImageRecord? = null
)

internal data class ContainerProfileAdditionUiState(
    val profileId: String,
    val profileName: String,
    val createsInstance: Boolean,
    val saved: Boolean = false,
    val inProgress: Boolean = true,
    val creatingInstance: Boolean = false,
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val error: Int? = null
)

internal fun immutableContainerProfile(profile: ContainerProfile): ContainerProfile = profile.copy(
    extraBindings = Collections.unmodifiableList(profile.extraBindings.toList()),
    extraArgs = Collections.unmodifiableList(profile.extraArgs.toList()),
    env = Collections.unmodifiableMap(profile.env.toMap())
)

/** A UUID identifies the new configuration; time is only display metadata, never its identity. */
internal fun newContainerProfile(profile: ContainerProfile): ContainerProfile = immutableContainerProfile(
    profile.copy(id = "custom-${UUID.randomUUID()}", createdAt = System.currentTimeMillis())
)

internal fun downloadedContainerProfileAddition(
    entry: ContainerImageEntry,
    record: DownloadedImageRecord,
    fileUri: String,
    runtime: LocalContainerRuntime,
    fallbackVersion: String?
): ContainerProfileAddition {
    require(record.entryId == entry.id && record.fileUri == fileUri && fileUri.isNotBlank()) {
        "The selected download changed"
    }
    val profile = requireNotNull(buildContainerProfile(
        initial = null,
        mode = ExecutionMode.LOCAL_PROOT,
        runtime = runtime,
        useBundledImage = false,
        name = entry.displayLabel(record.effectiveVersion().takeIf { it.isNotBlank() } ?: fallbackVersion),
        shellPath = "",
        bindings = emptyList(),
        args = emptyList(),
        env = emptyMap(),
        pickedUri = fileUri,
        selectedConnId = "",
        remotePath = ""
    ))
    return ContainerProfileAddition(newContainerProfile(profile), downloadedImage = record)
}

/**
 * Await every write before capturing the creation snapshot. onSaved runs only after upsert succeeded;
 * neither an imported download record nor a saved profile is evidence that an instance exists.
 */
internal suspend fun persistContainerProfileAddition(
    containers: ContainerSettingsRepository,
    addition: ContainerProfileAddition,
    onSaved: () -> Unit
): ContainerProfile {
    val profile = immutableContainerProfile(addition.profile.let {
        if (it.createdAt == 0L) it.copy(createdAt = System.currentTimeMillis()) else it
    })
    require(profile.id.isNotBlank() && !profile.isBuiltin && profile.id != ContainerProfile.BUILTIN_ID)
    require(profile.chrootInstance == null) { "Only a new configuration may be added" }
    if (profile.isChroot) {
        require(profile.extraArgs.isEmpty() && profile.extraBindings.isEmpty() &&
            profile.rootfsSource !is RootfsSource.RemoteSsh)
    }
    val snapshot = containers.captureProfileSnapshot()
    require(snapshot.profiles.none { it.id == profile.id }) { "The configuration already exists" }
    suspend fun checkDownload() {
        addition.downloadedImage?.let { expected ->
            require(profile.rootfsSource == RootfsSource.LocalFile(expected.fileUri))
            require(containers.downloadedImagesFlow.first().singleOrNull { it.entryId == expected.entryId } == expected) {
                "The selected download changed"
            }
        }
    }
    checkDownload()
    currentCoroutineContext().ensureActive()
    containers.checkProfileSnapshot(snapshot)
    containers.upsertCustomProfile(profile)
    onSaved()
    currentCoroutineContext().ensureActive()
    if (addition.setAsDefault && profile.mode == ExecutionMode.LOCAL_PROOT) {
        containers.setDefaultContainerId(profile.id)
    }
    addition.downloadedImage?.let { record ->
        checkDownload()
        currentCoroutineContext().ensureActive()
        containers.upsertDownloadedImage(record.copy(version = record.effectiveVersion(), installed = true))
    }
    return profile
}
