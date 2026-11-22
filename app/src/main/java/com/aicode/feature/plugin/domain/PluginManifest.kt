package com.aicode.feature.plugin.domain

import java.util.Locale

class LocalizedText(values: Map<String, String>) {
    val values: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))

    init { require(values.isNotEmpty()) }

    fun resolve(languageTag: String): String {
        val tag = normalize(languageTag)
        values[tag]?.let { return it }
        val language = tag.substringBefore('-')
        values[language]?.let { return it }
        if (language == "zh") {
            val locale = Locale.forLanguageTag(tag)
            val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO")
            val candidates = if (traditional) listOf("zh-hant", "zh-tw", "zh-hk", "zh-mo")
                else listOf("zh-hans", "zh-cn", "zh-sg")
            candidates.firstNotNullOfOrNull(values::get)?.let { return it }
        }
        return values[DEFAULT_KEY] ?: values["en"] ?: values.values.first()
    }

    override fun equals(other: Any?): Boolean = other is LocalizedText && values == other.values
    override fun hashCode(): Int = values.hashCode()

    companion object {
        const val DEFAULT_KEY = "default"
        fun single(text: String) = LocalizedText(mapOf(DEFAULT_KEY to text))
        fun normalize(tag: String): String = tag.trim().lowercase(Locale.ROOT).replace('_', '-')
    }
}

data class PluginContributionRef(val id: String, val file: String)
data class PluginDependencyRef(val tool: String, val file: String)
data class PluginRuntimeRef(
    val type: String,
    val entry: String,
    val command: List<String>? = null,
    val dependencies: PluginDependencyRef? = null,
    val resident: Boolean = false
)
data class PluginToolRef(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val inputSchema: String,
    val handler: String
)
enum class PluginUiEntryKind { PAGE, ACTION, DATA }
data class PluginUiEntryRef(
    val id: String,
    val slot: String,
    val title: LocalizedText,
    val page: String? = null,
    val handler: String? = null,
    val confirm: Boolean = false,
    val icon: String = "book"
) {
    val kind: PluginUiEntryKind get() = when {
        slot == PluginUiSlots.BACKUP_DATA -> PluginUiEntryKind.DATA
        page != null -> PluginUiEntryKind.PAGE
        else -> PluginUiEntryKind.ACTION
    }
}
data class PluginWorkspaceToolRef(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val icon: String = "play",
    val category: String = "general",
    val pageFile: String
)
object PluginRuntimeTypes {
    const val JAVASCRIPT = "javascript"
    const val PYTHON = "python"
    val ALL = setOf(JAVASCRIPT, PYTHON)
}
object PluginUiSlots {
    const val SETTINGS_GENERAL = "settings.general"
    const val SETTINGS_AI = "settings.ai"
    const val SETTINGS_ENVIRONMENT = "settings.environment"
    const val SETTINGS_PERMISSIONS = "settings.permissions"
    const val SETTINGS_DATA = "settings.data"
    const val SETTINGS_HELP = "settings.help"
    const val DRAWER_FOOTER = "drawer.footer"
    const val CHAT_HEADER_MENU = "chat.header.menu"
    const val CHAT_WELCOME = "chat.welcome"
    const val CHAT_SESSION_MENU = "chat.session.menu"
    const val FILES_TREE_MENU = "files.tree.menu"
    const val EDITOR_SELECTION_MENU = "editor.selection.menu"
    const val BACKUP_DATA = "backup.data"
    const val MAX_ENTRIES_PER_PLUGIN = 2
    val DATA_ONLY = setOf(BACKUP_DATA)
    val ALL = setOf(
        SETTINGS_GENERAL, SETTINGS_AI, SETTINGS_ENVIRONMENT, SETTINGS_PERMISSIONS, SETTINGS_DATA, SETTINGS_HELP,
        DRAWER_FOOTER, CHAT_HEADER_MENU, CHAT_WELCOME, CHAT_SESSION_MENU, FILES_TREE_MENU, EDITOR_SELECTION_MENU, BACKUP_DATA
    )
    val ACTION_ONLY = setOf(FILES_TREE_MENU, EDITOR_SELECTION_MENU)
    fun isSettingsSlot(slot: String): Boolean = slot.startsWith("settings.")
}
data class PluginManifest(
    val manifestVersion: Int,
    val id: String,
    val name: LocalizedText,
    val version: SemVer,
    val publisher: String,
    val repository: String,
    val description: LocalizedText,
    val license: String,
    val pluginApi: String,
    val skills: List<PluginContributionRef> = emptyList(),
    val agents: List<PluginContributionRef> = emptyList(),
    val workspaceTools: List<PluginWorkspaceToolRef> = emptyList(),
    val uiEntries: List<PluginUiEntryRef> = emptyList(),
    val runtime: PluginRuntimeRef? = null,
    val tools: List<PluginToolRef> = emptyList(),
    val configPage: String? = null,
    val icon: String? = null,
    val hooks: List<PluginHookRef> = emptyList(),
    val mcp: List<PluginMcpRef> = emptyList(),
    val providers: List<PluginProviderRef> = emptyList(),
    val sessionSources: List<PluginSessionSourceRef> = emptyList(),
    val shizuku: PluginShizukuRef? = null,
    val overlay: PluginOverlayRef? = null,
    val backup: PluginBackupRef? = null,
    val modelAccess: PluginModelAccessRef? = null,
    val permissions: Map<PluginCapability, String> = emptyMap()
) {
    val contributionCount: Int get() = skills.size + agents.size + workspaceTools.size + uiEntries.size +
        tools.size + hooks.size + mcp.size + providers.size + sessionSources.size +
        (if (shizuku != null) 1 else 0) + (if (overlay != null) 1 + overlay.surfaces.size else 0) +
        (if (backup != null) 1 else 0) + (if (modelAccess != null) 1 else 0) + (if (configPage != null) 1 else 0)
}
object PluginApi { val VERSION = SemVer(1, 0, 0) }
enum class PluginErrorCode {
    ARCHIVE_UNREADABLE, ARCHIVE_TOO_LARGE, ARCHIVE_TOO_MANY_ENTRIES, ARCHIVE_ENTRY_TOO_LARGE,
    ARCHIVE_UNSAFE_PATH, ARCHIVE_DUPLICATE_ENTRY, ARCHIVE_SYMLINK, MANIFEST_MISSING, MANIFEST_TOO_LARGE,
    MANIFEST_NOT_JSON, FIELD_MISSING, FIELD_INVALID, UNSUPPORTED_MANIFEST_VERSION, UNSUPPORTED_FIELD,
    UNSUPPORTED_CONTRIBUTION, API_INCOMPATIBLE, DUPLICATE_ID, FILE_MISSING, CONTENT_INVALID,
    STORAGE_FAILED, REGISTRY_CORRUPT, PREVIEW_EXPIRED, NOT_INSTALLED, TRUST_REQUIRED, TRUST_EXPIRED,
    STALE_CAPTURE, CONTENT_CHANGED, DEPENDENCIES_NOT_READY
}
class PluginError(val code: PluginErrorCode, detail: String = "") {
    val detail: String = detail.filterNot { it.isISOControl() }.take(160)
    override fun toString(): String = "$code:$detail"
}
sealed interface ManifestParseResult {
    data class Success(val manifest: PluginManifest) : ManifestParseResult
    data class Failure(val errors: List<PluginError>) : ManifestParseResult
}
