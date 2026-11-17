package com.aicode.feature.terminal.domain

internal class TerminalOutputState {
    var revision: Long = 0
        private set
    private var snapshotRevision = -1L
    private var snapshot = ""
    private var screen: Any? = null
    private var columns = -1
    private var rows = -1

    @Synchronized
    fun changed() { revision++ }

    @Synchronized
    fun observe(screen: Any?, columns: Int, rows: Int): Long {
        if (this.screen !== screen || this.columns != columns || this.rows != rows) {
            this.screen = screen
            this.columns = columns
            this.rows = rows
            changed()
        }
        return revision
    }

    @Synchronized
    fun read(screen: Any?, columns: Int, rows: Int, build: () -> String): String {
        observe(screen, columns, rows)
        if (snapshotRevision != revision) {
            snapshot = build()
            snapshotRevision = revision
        }
        return snapshot
    }
}

internal const val COMMAND_EXIT_MARKER = "[command exited: "

internal fun commandExitCode(output: String): Int? {
    var before = output.length
    while (before > 0) {
        val index = output.lastIndexOf(COMMAND_EXIT_MARKER, before - 1)
        if (index < 0) return null
        val start = index + COMMAND_EXIT_MARKER.length
        val end = output.indexOf(']', start)
        if ((index == 0 || output[index - 1] == '\n' || output[index - 1] == '\r') && end >= start &&
            (end == output.lastIndex || output[end + 1] == '\n' || output[end + 1] == '\r')) {
            val digits = output.substring(start, end)
            if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
                digits.toIntOrNull()?.let { return it }
            }
        }
        before = index
    }
    return null
}
