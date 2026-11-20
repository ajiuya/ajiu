package com.aicode.feature.plugin.domain

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList()
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.compareTo(preRelease.size).coerceIn(-1, 1)
        }
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            compareId(preRelease[index], other.preRelease[index]).let { if (it != 0) return it }
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }
    override fun toString(): String = "$major.$minor.$patch" +
        if (preRelease.isEmpty()) "" else "-${preRelease.joinToString(".")}"

    companion object {
        private val PATTERN = Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
        )
        fun parse(text: String): SemVer? {
            if (text.length > 128) return null
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            val ids = pre.takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            if (ids.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return SemVer(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null, pluginImmutableList(ids))
        }
        private fun compareId(a: String, b: String): Int {
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            return when {
                an && bn -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

class SemVerRange private constructor(private val bounds: List<Pair<String, SemVer>>) {
    fun matches(version: SemVer): Boolean = bounds.all { (op, bound) ->
        val result = version.compareTo(bound)
        when (op) { ">=" -> result >= 0; ">" -> result > 0; "<=" -> result <= 0; "<" -> result < 0; else -> result == 0 }
    }
    companion object {
        fun parse(text: String): SemVerRange? {
            if (text.isBlank() || text.length > 256) return null
            val bounds = mutableListOf<Pair<String, SemVer>>()
            for (token in text.trim().split(Regex("\\s+"))) {
                val op = listOf(">=", "<=", ">", "<", "=", "^", "~").firstOrNull { token.startsWith(it) } ?: ""
                val base = SemVer.parse(token.removePrefix(op)) ?: return null
                if (op == "^" || op == "~") {
                    val component = when {
                        op == "~" -> base.minor
                        base.major > 0 -> base.major
                        base.minor > 0 -> base.minor
                        else -> base.patch
                    }
                    if (component == Int.MAX_VALUE) return null
                    val upper = when {
                        op == "~" -> SemVer(base.major, base.minor + 1, 0)
                        base.major > 0 -> SemVer(base.major + 1, 0, 0)
                        base.minor > 0 -> SemVer(0, base.minor + 1, 0)
                        else -> SemVer(0, 0, base.patch + 1)
                    }
                    bounds += ">=" to base
                    bounds += "<" to upper
                } else bounds += op to base
            }
            return SemVerRange(bounds)
        }
    }
}
