package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.subagent.BuiltinAgentPublication
import kotlinx.coroutines.CancellationException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Locale
import java.util.UUID

data class BuiltinInstallReport(val entries: Map<String, BuiltinInstallStatus> = emptyMap())
enum class BuiltinInstallStatus { INSTALLED, PRESERVED_EXISTING, ALREADY_HANDLED, FAILED }

internal enum class BuiltinNode { MISSING, FILE, DIRECTORY, OTHER }

internal interface BuiltinDefinitionStore {
    /** All operations validate ancestors; node/readDefinition never follow the final symlink. */
    fun node(path: String): BuiltinNode
    fun children(path: String): List<String>
    fun readDefinition(path: String, maxBytes: Int): ByteArray?
    fun ensureDirectory(path: String)
    fun createDirectory(path: String)
    fun writeNew(path: String, bytes: ByteArray)
    fun publish(staged: String, target: String, directory: Boolean): Boolean
    /** Only unlink an entry created by this store, without recursion. */
    fun remove(path: String)
    fun isReceipt(path: String): Boolean
}

internal object BuiltinDefinitionPaths {
    const val RECEIPTS = ".builtin-install-receipts-v1"
    const val MAX_DEFINITION_BYTES = 256 * 1024
    const val MAX_SCAN_ENTRIES = 4096

    fun component(value: String) {
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 255 && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) { "Invalid builtin path component" }
    }

    fun relative(value: String) {
        require(value.isNotEmpty() && !value.startsWith('/') && !value.endsWith('/')) { "Invalid builtin path" }
        value.split('/').forEach(::component)
    }

    fun managed(value: String) {
        relative(value)
        require(value.substringBefore('/') in setOf("agents", "skills", RECEIPTS)) { "Not a builtin definition path" }
    }

    fun validate(agents: Map<String, ByteArray>, skills: Map<String, Map<String, ByteArray>>) {
        val agentNames = HashSet<String>()
        agents.forEach { (file, bytes) ->
            component(file)
            require(file.endsWith(".md") && !file.startsWith('.'))
            val name = definitionName(bytes, file.removeSuffix(".md"))
            require(name != null && name == normalize(file.removeSuffix(".md"))) { "Bundled agent filename/name mismatch" }
            component(name)
            require(agentNames.add(name)) { "Duplicate bundled agent name" }
        }
        val skillNames = HashSet<String>()
        skills.forEach { (name, files) ->
            component(name)
            require(!name.startsWith('.') && files.containsKey("SKILL.md")) { "Incomplete bundled skill" }
            val logicalName = definitionName(files.getValue("SKILL.md"), name)
            require(logicalName != null && skillNames.add(logicalName)) { "Invalid or duplicate bundled skill name" }
            component(logicalName)
            files.keys.forEach(::relative)
            files.keys.forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(parent !in files) { "Conflicting bundled skill paths" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }
    }

    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    /** Existing definitions are untrusted data: no YAML object construction, aliases or unbounded reads. */
    fun definitionName(bytes: ByteArray, fallback: String): String? {
        if (bytes.size > MAX_DEFINITION_BYTES) return null
        val text = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return normalize(fallback)
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return normalize(fallback)
        return try {
            val options = LoaderOptions().apply {
                codePointLimit = MAX_DEFINITION_BYTES
                maxAliasesForCollections = 0
                nestingDepthLimit = 20
            }
            val loaded = Yaml(SafeConstructor(options)).load<Any>(text.substring(4, end))
            if (loaded != null && loaded !is Map<*, *>) return null
            val name = (loaded as? Map<*, *>)?.get("name")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            normalize(name ?: fallback)
        } catch (_: Exception) {
            null
        }
    }
}

/** Defaults are offered once per logical name, never mirrored or upgraded. Receipts retain deletions. */
internal class BuiltinDefinitionInstaller(
    private val store: BuiltinDefinitionStore,
    private val checkActive: () -> Unit = ::checkBuiltinInstallThread,
    private val onFailure: (String, Exception) -> Unit = { _, _ -> }
) {
    fun install(
        agents: Map<String, ByteArray> = emptyMap(),
        skills: Map<String, Map<String, ByteArray>> = emptyMap()
    ): BuiltinInstallReport {
        BuiltinDefinitionPaths.validate(agents, skills)
        val result = linkedMapOf<String, BuiltinInstallStatus>()
        agents.forEach { (file, bytes) ->
            result["agents/$file"] = offer("agents", file, BuiltinDefinitionPaths.definitionName(bytes, file.removeSuffix(".md"))!!,
                mapOf("definition" to bytes), false)
        }
        skills.forEach { (name, files) ->
            result["skills/$name"] = offer("skills", name, BuiltinDefinitionPaths.definitionName(files.getValue("SKILL.md"), name)!!,
                files, true)
        }
        return BuiltinInstallReport(result)
    }

    private fun existingName(category: String, name: String): Boolean {
        var visited = 0
        var uncertain = false
        fun candidates(path: String, depth: Int): Boolean {
            checkActive()
            val entries = store.children(path).sorted()
            visited += entries.size
            check(visited <= BuiltinDefinitionPaths.MAX_SCAN_ENTRIES) { "Too many existing builtin candidates" }
            if (category == "skills") {
                val definition = entries.firstOrNull { it.equals("SKILL.md", ignoreCase = true) }
                    ?: entries.firstOrNull { it.equals("CLAUDE.md", ignoreCase = true) }
                if (definition != null) {
                    val bytes = store.readDefinition("$path/$definition", BuiltinDefinitionPaths.MAX_DEFINITION_BYTES)
                    val found = bytes?.let { BuiltinDefinitionPaths.definitionName(it, path.substringAfterLast('/')) }
                    if (found == name) return true
                    if (found == null) uncertain = true
                }
            }
            for (entry in entries) {
                checkActive()
                if (runCatching { BuiltinDefinitionPaths.component(entry) }.isFailure) {
                    uncertain = true
                    continue
                }
                val child = "$path/$entry"
                val type = store.node(child)
                if (category == "agents" && entry.endsWith(".md", ignoreCase = true) && type != BuiltinNode.DIRECTORY) {
                    val bytes = store.readDefinition(child, BuiltinDefinitionPaths.MAX_DEFINITION_BYTES)
                    val found = bytes?.let { BuiltinDefinitionPaths.definitionName(it, entry.substringBeforeLast('.')) }
                    if (found == name) return true
                    if (found == null) uncertain = true
                } else if (category == "skills") {
                    if (type == BuiltinNode.OTHER) uncertain = true
                    if (type == BuiltinNode.DIRECTORY && depth < 4 && candidates(child, depth + 1)) return true
                }
            }
            return false
        }
        // An unreadable/linked candidate may own this name. Adopt conservatively, never follow it.
        return candidates(category, 0) || uncertain
    }

    private fun offer(category: String, filename: String, name: String, files: Map<String, ByteArray>, directory: Boolean): BuiltinInstallStatus {
        val target = "$category/$filename"
        BuiltinDefinitionPaths.component(name)
        val receipt = "${BuiltinDefinitionPaths.RECEIPTS}/$category/$name"
        // The recursive skill scanner must never see incomplete SKILL.md or abandoned staging trees.
        val stage = "${BuiltinDefinitionPaths.RECEIPTS}/$category/.builtin-stage-${UUID.randomUUID()}"
        val created = ArrayList<String>()
        var claimedReceipt = false
        var publicationCompleted = false
        fun mkdir(path: String) { store.createDirectory(path); created.add(path) }
        fun writeReceipt(): Boolean {
            checkActive()
            try {
                store.writeNew(receipt, byteArrayOf())
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                checkActive()
                if (!store.isReceipt(receipt)) throw e
                return false
            }
        }
        try {
            checkActive()
            store.ensureDirectory(category)
            store.ensureDirectory(BuiltinDefinitionPaths.RECEIPTS)
            store.ensureDirectory("${BuiltinDefinitionPaths.RECEIPTS}/$category")
            if (store.node(receipt) != BuiltinNode.MISSING) {
                check(store.isReceipt(receipt)) { "Unsafe builtin receipt" }
                return BuiltinInstallStatus.ALREADY_HANDLED
            }
            if (store.node(target) != BuiltinNode.MISSING || existingName(category, name)) {
                writeReceipt()
                return BuiltinInstallStatus.PRESERVED_EXISTING
            }
            mkdir(stage)
            val payload = "$stage/payload"
            if (directory) mkdir(payload)
            val directories = HashSet<String>()
            files.forEach { (relative, bytes) ->
                checkActive()
                val dest = if (directory) "$payload/$relative" else payload
                if (directory) {
                    var parent = payload
                    relative.split('/').dropLast(1).forEach { part ->
                        parent += "/$part"
                        if (directories.add(parent)) mkdir(parent)
                    }
                }
                created.add(dest)
                store.writeNew(dest, bytes)
            }
            checkActive()
            // Recheck logical ownership after staging: user saves during a long package copy win.
            if (existingName(category, name)) {
                writeReceipt()
                return BuiltinInstallStatus.PRESERVED_EXISTING
            }
            // Claim before exposure: a receipt IO failure must never leave a visible untracked default.
            // A hard kill after this claim conservatively retains the tombstone even without payload.
            if (!writeReceipt()) return BuiltinInstallStatus.ALREADY_HANDLED
            claimedReceipt = true
            val installed = store.publish(payload, target, directory)
            publicationCompleted = true
            if (installed && category == "agents") BuiltinAgentPublication.notifyChanged()
            return if (installed) BuiltinInstallStatus.INSTALLED else BuiltinInstallStatus.PRESERVED_EXISTING
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            checkActive()
            onFailure(target, e)
            return BuiltinInstallStatus.FAILED
        } finally {
            if (claimedReceipt && !publicationCompleted) {
                runCatching {
                    if (store.node(target) == BuiltinNode.MISSING) store.remove(receipt)
                }
            }
            created.asReversed().forEach { path -> runCatching { store.remove(path) } }
        }
    }
}

internal fun checkBuiltinInstallThread() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Builtin installation cancelled")
}
