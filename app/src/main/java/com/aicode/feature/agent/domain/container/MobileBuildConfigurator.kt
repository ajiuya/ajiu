package com.aicode.feature.agent.domain.container

import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.MobileBuildSettingsRepository
import com.aicode.feature.settings.domain.model.MobileBuildSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MobileBuildConfigurator @Inject constructor(
    private val settingsRepository: MobileBuildSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerInstaller: ContainerInstaller
) {
    private val lock = Any()

    suspend fun update(transform: (MobileBuildSettings) -> MobileBuildSettings) = withContext(Dispatchers.IO) {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
            .let { profiles ->
                if (profiles.any { it.id == ContainerProfile.BUILTIN_ID }) profiles
                else profiles + ContainerProfile.BUILTIN_ALPINE
            }
        synchronized(lock) {
            val next = transform(settingsRepository.settings.value)
            require(next.gradleHeapMb in MobileBuildSettings.GRADLE_HEAP_OPTIONS)
            require(next.maxWorkers in MobileBuildSettings.WORKER_OPTIONS)
            require(next.testHeapMb in MobileBuildSettings.TEST_HEAP_OPTIONS)
            val changes = profiles.distinctBy { it.id }.flatMap { changesFor(it, next) }
            applyChanges(changes) { settingsRepository.save(next) }
        }
    }

    fun prepare(profile: ContainerProfile) {
        synchronized(lock) {
            applyChanges(changesFor(profile, settingsRepository.settings.value))
        }
    }

    private fun changesFor(profile: ContainerProfile, settings: MobileBuildSettings): List<MobileBuildFileChange> {
        if (profile.mode != ExecutionMode.LOCAL_PROOT || profile.rootfsSource is RootfsSource.RemoteSsh) return emptyList()
        if (!containerInstaller.isInstalledFor(profile)) return emptyList()
        if (settings.enabled) {
            val home = profile.env["HOME"]
            val gradleHome = profile.env["GRADLE_USER_HOME"]
            if (home != null && home != "/root" || gradleHome != null && gradleHome != "/root/.gradle") return emptyList()
            if (profile.extraArgs.any {
                    it == "-b" || it.startsWith("-b") || it.startsWith("--bind") ||
                        it == "-r" || it.startsWith("-r") || it.startsWith("--rootfs") ||
                        it == "-R" || it.startsWith("-R") || it.startsWith("-S")
                } || profile.extraBindings.withIndex().any { (index, binding) ->
                    val destination = binding.substringAfter(':', binding).trimEnd('!')
                    if (!destination.startsWith('/')) throw IOException("Container binding target must be absolute")
                    val target = File(destination).toPath().normalize().toString().trimEnd('/')
                    val mountedParents = listOf("/dev", "/proc", "/sys", "/system", "/root/workspace", "/root/.aicode") +
                        profile.extraBindings.filterIndexed { other, _ -> other != index }.map {
                            File(it.substringAfter(':', it).trimEnd('!')).toPath().normalize().toString().trimEnd('/')
                        }
                    if (mountedParents.any { it.isEmpty() || target == it || target.startsWith("$it/") }) {
                        throw IOException("Container binding target crosses another mounted directory")
                    }
                    val rootfs = containerInstaller.rootfsDirFor(profile)
                    val segments = mutableListOf<String>()
                    destination.split('/').forEach { part ->
                        when (part) {
                            "", "." -> Unit
                            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                            else -> segments += part
                        }
                        if (Files.isSymbolicLink(File(rootfs, segments.joinToString("/")).toPath())) {
                            throw IOException("Container binding target contains a symbolic link")
                        }
                    }
                    target.isEmpty() || target == "/root" || target.startsWith("/root/.gradle/") || target == "/root/.gradle"
                }) {
                throw IOException("Default Gradle user directory is redirected by a container binding")
            }
        }
        return MobileBuildGradleFiles.changes(containerInstaller.rootfsDirFor(profile), settings)
    }

    private fun applyChanges(changes: List<MobileBuildFileChange>, persist: () -> Unit = {}) {
        val applied = mutableListOf<MobileBuildFileChange>()
        try {
            changes.forEach { change ->
                if (change.currentContent() != change.before) throw IOException("Gradle configuration changed during update")
                change.write(change.after)
                applied += change
            }
            persist()
        } catch (failure: Exception) {
            applied.asReversed().forEach { change ->
                try {
                    if (change.currentContent() != change.after) throw IOException("Gradle configuration changed during rollback")
                    if (change.before == null) Files.deleteIfExists(change.file.toPath())
                    else change.write(change.before)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }
}

internal data class MobileBuildFileChange(val file: File, val before: String?, val after: String, private val rootfs: File) {
    fun currentContent(): String? {
        checkPath()
        return if (file.exists()) file.readText(Charsets.ISO_8859_1) else null
    }

    fun write(content: String) {
        checkPath()
        file.parentFile!!.mkdirs()
        checkPath()
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), ".ajiu-mobile-", ".tmp")
        try {
            Files.write(temporary, content.toByteArray(Charsets.ISO_8859_1))
            checkPath()
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun checkPath() {
        MobileBuildGradleFiles.checkedFile(rootfs, file.relativeTo(rootfs).path)
    }
}

internal object MobileBuildGradleFiles {
    const val BEGIN = "# >>> Ajiu Workbench mobile build >>>"
    const val END = "# <<< Ajiu Workbench mobile build <<<"
    const val INIT_SCRIPT_NAME = "ajiu-mobile-build.gradle"
    val INIT_SCRIPT = """
        // Ajiu Workbench mobile build
        import org.gradle.api.tasks.testing.Test

        gradle.projectsEvaluated {
            def root = gradle.rootProject
            if (root.findProperty('aicodeMobileBuild')?.toString() == 'true') {
                def heap = root.findProperty('aicodeMobileTestHeap')?.toString()
                if (heap == null || !(heap ==~ /[1-9][0-9]*[mMgG]/)) {
                    throw new GradleException('Invalid mobile test heap')
                }
                root.allprojects { project ->
                    project.tasks.withType(Test).configureEach {
                        maxHeapSize = heap
                        maxParallelForks = 1
                    }
                }
            }
        }
    """.trimIndent() + "\n"

    fun changes(rootfs: File, settings: MobileBuildSettings): List<MobileBuildFileChange> {
        val properties = checkedFile(rootfs, "root/.gradle/gradle.properties")
        val previous = if (properties.exists()) properties.readText(Charsets.ISO_8859_1) else null
        val updated = properties(previous.orEmpty(), settings)
        if (!settings.enabled) {
            return if (previous != null && updated != previous) {
                listOf(MobileBuildFileChange(properties, previous, updated, rootfs))
            } else emptyList()
        }
        val init = checkedFile(rootfs, "root/.gradle/init.d/$INIT_SCRIPT_NAME")
        val oldInit = if (init.exists()) init.readText(Charsets.ISO_8859_1) else null
        if (oldInit != null && oldInit != INIT_SCRIPT) throw IOException("Mobile build init script conflicts with an existing file")
        return buildList {
            if (oldInit == null) add(MobileBuildFileChange(init, null, INIT_SCRIPT, rootfs))
            if (previous != updated) add(MobileBuildFileChange(properties, previous, updated, rootfs))
        }
    }

    fun properties(original: String, settings: MobileBuildSettings): String {
        val starts = Regex("(?:\\A|(?<=\\n))${Regex.escape(BEGIN)}\\r?(?=\\n|\\z)").findAll(original).toList()
        val ends = Regex("(?:\\A|(?<=\\n))${Regex.escape(END)}\\r?(?=\\n|\\z)").findAll(original).toList()
        val base = if (starts.isEmpty() && ends.isEmpty()) {
            original
        } else {
            if (starts.size != 1 || ends.size != 1) throw IOException("Invalid mobile build property markers")
            val start = starts.single().range.first
            val end = ends.single().range.last + 1
            if (start < 2 || original.substring(start - 2, start) != "\n\n" || end <= start || original.getOrNull(end) != '\n') {
                throw IOException("Invalid mobile build property block")
            }
            // Later user properties still need the separator when the original prefix had no final newline.
            val removalStart = if (end + 1 == original.length) start - 2 else start
            original.removeRange(removalStart, end + 1)
        }
        if (!settings.enabled) return base
        // Two blank lines also terminate a trailing properties continuation without changing the original bytes.
        return base + "\n\n" + """
            $BEGIN
            org.gradle.jvmargs=-Xmx${settings.gradleHeapMb}m -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC
            org.gradle.daemon=${settings.daemonEnabled}
            org.gradle.parallel=${settings.parallelEnabled}
            org.gradle.workers.max=${settings.maxWorkers}
            org.gradle.configuration-cache=false
            kotlin.compiler.execution.strategy=in-process
            aicodeMobileBuild=true
            aicodeMobileTestHeap=${settings.testHeapMb}m
            $END
        """.trimIndent() + "\n"
    }

    fun checkedFile(rootfs: File, relative: String): File {
        if (Files.isSymbolicLink(rootfs.toPath())) throw IOException("Container root contains a symbolic link")
        var current = rootfs
        relative.split('/').forEach { part ->
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) throw IOException("Gradle configuration path contains a symbolic link")
        }
        if (current.exists() && !current.isFile) throw IOException("Gradle configuration path is not a file")
        return current
    }
}
