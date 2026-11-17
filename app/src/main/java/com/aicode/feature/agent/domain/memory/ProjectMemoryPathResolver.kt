package com.aicode.feature.agent.domain.memory

import java.io.File
import javax.inject.Inject

class ProjectMemoryPathResolver @Inject constructor() {
    fun resolve(identity: ProjectMemoryIdentity, rawPath: String): String? = freeze(identity)(rawPath)

    fun freeze(identity: ProjectMemoryIdentity): (String) -> String? {
        val remote = identity.mode.equals("remote_ssh", true)
        val original = identity.projectRoot
        val root = if (remote) ProjectMemoryIdentityResolver.normalizeRoot(original) else File(original).canonicalPath
        return resolver@ { raw ->
            if (raw.isBlank() || raw.length > 4096 || raw.any { it.isISOControl() || it == '\\' }) return@resolver null
            if (!remote && File(original).canonicalPath != root) return@resolver null
            val path = when {
                raw == "~/workspace" -> root
                raw.startsWith("~/workspace/") -> "$root/${raw.removePrefix("~/workspace/")}"
                !remote && raw == "/root/workspace" -> root
                !remote && raw.startsWith("/root/workspace/") -> "$root/${raw.removePrefix("/root/workspace/")}"
                raw.startsWith('~') -> return@resolver null
                raw.startsWith('/') -> raw
                else -> "$root/$raw"
            }
            try {
                // SSH containment is lexical attribution only, not a remote symlink sandbox.
                val resolved = if (remote) ProjectMemoryIdentityResolver.normalizeRoot(path) else File(path).canonicalPath
                resolved.takeIf { ProjectMemoryIdentityResolver.containsRoot(root, it) }
            } catch (_: java.io.IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
