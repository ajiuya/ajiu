package com.aicode.feature.agent.domain.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/**
 * Conservative lexical evidence, not an execution-identity check or a sandbox. Never opens paths,
 * resolves symlinks or runs code. READ evidence alone needs no host-write approval; WRITE,
 * READ_WRITE or any uncertainty does. Guest /etc, /usr and /var are not Android host paths.
 */
object HostSystemFileAccessClassifier {
    enum class Namespace { HOST, CHROOT_GUEST }

    /** Classifier results contain defensive, unmodifiable snapshots, with ordinary value equality. */
    data class Assessment(val targets: Set<String>, val uncertainties: Set<String>)

    private enum class Access { READ, WRITE, READ_WRITE }
    private val hostRoots = setOf(
        "/system", "/system_ext", "/vendor", "/product", "/odm", "/oem", "/apex",
        "/etc", "/metadata", "/persist", "/data/system", "/data/misc", "/data/adb", "/data/property"
    )
    private val interfaces = setOf("/proc", "/sys", "/dev")
    private val harmlessDevices = setOf("/dev/null", "/dev/zero", "/dev/stdin", "/dev/stdout", "/dev/stderr")
    // Only conventional lexical locations, not proof of the executable's actual identity or PATH.
    private val executableDirectories = setOf("/system/bin", "/system/xbin", "/bin", "/sbin", "/usr/bin", "/usr/sbin")
    private val readers = setOf(
        "cat", "head", "tail", "wc", "stat", "ls", "du", "readlink", "realpath", "file",
        "hexdump", "od", "strings", "cmp", "diff", "sha256sum", "sha1sum", "md5sum", "getfattr"
    )
    private val ordinary = setOf("id", "echo", "printf", "true", "false", ":", "pwd", "uname", "ps", "whoami", "groups", "uptime", "sleep", "getprop")
    private val writers = setOf("touch", "rm", "rmdir", "mkdir", "chmod", "chown", "chgrp", "truncate", "shred", "tee", "restorecon", "setfattr")
    private val transfers = setOf("cp", "mv", "ln", "install")
    private val shells = setOf("sh", "bash", "dash", "ash", "zsh", "ksh", "su")
    private val codePrograms = setOf("awk", "gawk", "mawk", "python", "python3", "perl", "ruby", "node", "less", "more")
    private val wrappers = setOf("command", "exec", "env", "nohup", "busybox", "toybox", "sudo")
    private val assignment = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    private val startupVariables = setOf("BASH_ENV", "ENV", "LD_PRELOAD", "LD_AUDIT", "LD_LIBRARY_PATH", "PYTHONSTARTUP", "NODE_OPTIONS", "PERL5OPT", "RUBYOPT")
    private val syntaxBlockers = setOf(
        ShellCommandParser.BlockerKind.SUBSHELL_OR_GROUP, ShellCommandParser.BlockerKind.COMPOUND_COMMAND,
        ShellCommandParser.BlockerKind.UNTERMINATED_ESCAPE, ShellCommandParser.BlockerKind.UNTERMINATED_QUOTE,
        ShellCommandParser.BlockerKind.MISSING_REDIRECT_TARGET, ShellCommandParser.BlockerKind.UNKNOWN_FILE_DESCRIPTOR,
        ShellCommandParser.BlockerKind.UNKNOWN_OUTPUT_REDIRECT
    )
    private val valueOptions = mapOf(
        "head" to setOf("-n", "--lines", "-c", "--bytes"),
        "tail" to setOf("-n", "--lines", "-c", "--bytes", "--pid", "-s", "--sleep-interval", "--max-unchanged-stats"),
        "stat" to setOf("-c", "--format", "--printf"),
        "ls" to setOf("-I", "--ignore", "--hide", "--format", "--sort", "--time", "--time-style", "--color", "--block-size", "-T", "--tabsize", "-w", "--width", "--quoting-style", "--indicator-style"),
        "du" to setOf("-d", "--max-depth", "-B", "--block-size", "--threshold", "--exclude"),
        "file" to setOf("-e", "--exclude", "-F", "--separator", "-P", "--parameter"),
        "hexdump" to setOf("-e", "-f", "-n", "-s"),
        "od" to setOf("-A", "--address-radix", "-j", "--skip-bytes", "-N", "--read-bytes", "-t", "--format", "-w", "--width"),
        "strings" to setOf("-n", "--bytes", "-t", "--radix", "-e", "--encoding", "-T", "--target", "-s", "--output-separator"),
        "cmp" to setOf("-i", "--ignore-initial", "-n", "--bytes"),
        "diff" to setOf("-I", "--ignore-matching-lines", "-L", "--label", "-x", "--exclude", "--ifdef", "--line-format", "--old-line-format", "--new-line-format", "--unchanged-line-format"),
        "getfattr" to setOf("-n", "--name", "-m", "--match", "-e", "--encoding"),
        "touch" to setOf("-d", "--date", "-t"),
        "mkdir" to setOf("-m", "--mode"),
        "chown" to setOf("--from"),
        "truncate" to setOf("-s", "--size"),
        "shred" to setOf("-s", "--size", "-n", "--iterations"),
        "setfattr" to setOf("-n", "--name", "-v", "--value", "-x", "--remove", "-e", "--encoding"),
        "date" to setOf("-d", "--date", "-s", "--set"),
        "cp" to setOf("-t", "--target-directory", "-S", "--suffix"),
        "mv" to setOf("-t", "--target-directory", "-S", "--suffix"),
        "ln" to setOf("-t", "--target-directory", "-S", "--suffix"),
        "install" to setOf("-t", "--target-directory", "-S", "--suffix", "-m", "--mode", "-o", "--owner", "-g", "--group", "--strip-program")
    )
    private fun flags(short: String, vararg long: String): Set<String> = short.map { "-$it" }.toSet() + long
    private val optionFlags = mapOf(
        "command" to flags("pvV"),
        "file" to flags("bCcdEhikLlNnprsv0", "--brief", "--compile", "--checking-printout", "--debug", "--error", "--no-dereference", "--mime", "--mime-type", "--mime-encoding", "--keep-going", "--dereference", "--list", "--no-pad", "--no-buffer", "--preserve-date", "--raw", "--special-files", "--version", "--print0", "--help"),
        "ln" to flags("bdfinrsvLPT", "--backup", "--directory", "--force", "--interactive", "--no-dereference", "--relative", "--symbolic", "--verbose", "--logical", "--physical", "--no-target-directory", "--help", "--version"),
        "cp" to flags("abdfHilLnPprRstTuvx", "--archive", "--backup", "--force", "--interactive", "--link", "--no-clobber", "--dereference", "--no-dereference", "--parents", "--recursive", "--symbolic-link", "--no-target-directory", "--update", "--verbose", "--one-file-system", "--help", "--version"),
        "mv" to flags("bfinTuv", "--backup", "--force", "--interactive", "--no-clobber", "--no-target-directory", "--update", "--verbose", "--help", "--version"),
        "install" to flags("bcdDpsTv", "--backup", "--compare", "--directory", "--preserve-timestamps", "--strip", "--no-target-directory", "--verbose", "--help", "--version")
    )

    private data class OptionScan(
        val names: Set<String>,
        val values: List<Pair<String, String?>>,
        val operands: List<String>,
        val uncertain: Boolean
    )

    /** Names, values and operands share one walk: a value ends its cluster, even if it looks like flags. */
    private fun scanOptions(
        args: List<String>,
        valued: Set<String>,
        knownFlags: Set<String>?,
        leadingOnly: Boolean,
        literalOperand: (String) -> Boolean = { false }
    ): OptionScan {
        val names = linkedSetOf<String>()
        val values = mutableListOf<Pair<String, String?>>()
        val operands = mutableListOf<String>()
        var uncertain = false
        var options = true
        var index = 0
        while (index < args.size) {
            val arg = args[index++]
            if (options && arg == "--") { options = false; continue }
            if (!options || !arg.startsWith('-') || arg == "-" || literalOperand(arg)) {
                if (leadingOnly) break
                operands.add(arg)
                continue
            }
            var offset = 1
            while (offset < arg.length) {
                val long = arg.startsWith("--")
                val name = if (long) arg.substringBefore('=') else "-${arg[offset++]}"
                names.add(name)
                if (name in valued) {
                    val value = when {
                        long && '=' in arg -> arg.substringAfter('=')
                        !long && offset < arg.length -> arg.substring(offset)
                        else -> args.getOrNull(index++)
                    }
                    values.add(name to value)
                    if (value == null) uncertain = true
                    break
                }
                // Only programs with a finite flag table had flag validation before this shared walk.
                if (knownFlags != null && (name !in knownFlags || (long && '=' in arg))) uncertain = true
                if (long) break
            }
        }
        return OptionScan(names, values, operands, uncertain)
    }

    fun assessCommand(command: String, namespace: Namespace): Assessment =
        Evidence(namespace).apply { visit(command, null, true, 0) }.snapshot()

    fun commandTargets(command: String, namespace: Namespace): Set<String> = assessCommand(command, namespace).targets

    /** Uses schema-defined operands only; content, edits, names and prompts are never path-scanned. */
    fun assessFileTool(toolName: String, args: Map<String, JsonElement>, namespace: Namespace): Assessment {
        val evidence = Evidence(namespace)
        fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun record(value: JsonElement?, access: Access) {
            val path = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            evidence.record(path, access, null, false, typed = true)
        }
        when (toolName) {
            "readFile" -> record(args["path"], Access.READ)
            "writeFile", "editFile" -> record(args["path"], Access.WRITE)
            "sendFile", "viewImage" -> {
                val key = if (toolName == "sendFile") "paths" else "images"
                (args[key] as? JsonArray)?.forEach { record(it, Access.READ) }
            }
            "generateImage" -> record(args["output_path"], Access.WRITE)
            "list", "search" -> evidence.visit((if (toolName == "list") "ls " else "rg ") + text("args").orEmpty(), null, true, 0)
            "Bash", "Root", "Shizuku" -> text("command")?.let { evidence.visit(it, null, true, 0) }
                ?: evidence.unknown("opaque-shell-input")
            "terminal" -> when (text("action")) {
                "start" -> text("command")?.let { evidence.visit(it, null, true, 0) }
                    ?: evidence.unknown("opaque-shell-input")
                // Input may go to an interpreter, editor or an existing shell with unknown state.
                "send" -> evidence.unknown("interactive-input")
                else -> Unit
            }
        }
        return evidence.snapshot()
    }

    fun fileToolTargets(toolName: String, args: Map<String, JsonElement>, namespace: Namespace): Set<String> =
        assessFileTool(toolName, args, namespace).targets

    private class Evidence(private val namespace: Namespace) {
        private val targets = linkedSetOf<String>()
        private val uncertainties = linkedSetOf<String>()
        fun unknown(reason: String) { uncertainties.add(reason) }
        fun snapshot() = Assessment(immutable(targets), immutable(uncertainties))

        fun record(raw: String, access: Access, cwd: String?, uncertainCwd: Boolean, typed: Boolean = false) {
            val writing = access != Access.READ
            if (!typed && writing && dynamicPath(raw)) unknown("dynamic-write-path")
            if (writing && !raw.startsWith('/') && (!typed || !workspacePath(raw))) {
                if (cwd == null || uncertainCwd) unknown("unknown-write-directory")
            }
            val path = if (typed) raw.takeIf { it.startsWith('/') }?.let(::normalize) else literalPath(raw, cwd)
            if (path != null) {
                if (writing && namespace == Namespace.CHROOT_GUEST && path in setOf("/", "/*")) unknown("guest-root-write")
                target(path, access, namespace)?.let(targets::add)
            }
        }

        fun visit(command: String, initialDirectory: String?, initialUncertain: Boolean, depth: Int) {
            if (depth > 32) { unknown("analysis-depth"); return }
            val analysis = ShellCommandParser.analyze(command)
            if (analysis.blockers.any { it.kind in syntaxBlockers }) unknown("unsupported-shell-syntax")
            val dynamicWords = analysis.blockers.filter { it.kind == ShellCommandParser.BlockerKind.DYNAMIC_ARGUMENT }.mapNotNull { it.detail }.toSet()
            // The parser does not expose control-flow edges. Only a simple && cd chain proves cwd.
            val uncertainFlow = command.any { it == ';' || it == '\n' || it == '|' } || Regex("(?<!&)&(?!&)").containsMatchIn(command)
            var cwd = initialDirectory
            var uncertainCwd = initialUncertain
            for (segment in analysis.commandSegments) {
                for (redirect in segment.redirections) {
                    if (redirect.operator.startsWith("<<")) continue // Here-data, not a path/script.
                    val fd = redirect.target.removeSuffix("-")
                    if (redirect.literal && redirect.operator in setOf(">&", "<&") &&
                        (redirect.target == "-" || (fd.isNotEmpty() && fd.all { it in '0'..'9' }))) continue
                    val access = when {
                        redirect.operator == "<>" -> Access.READ_WRITE
                        '>' in redirect.operator -> Access.WRITE
                        else -> Access.READ
                    }
                    if (!redirect.literal && access != Access.READ) unknown("dynamic-write-path")
                    record(redirect.target, access, cwd, uncertainCwd)
                }
                if (segment.tokens.takeWhile { assignment.matches(it) }.any { it.substringBefore('=') in startupVariables }) unknown("startup-environment")
                val effective = ShellCommandParser.effectiveTokens(segment.tokens)
                val executable = effective.firstOrNull() ?: continue
                if (program(executable) == null) { unknown("untrusted-executable-path"); continue }
                // Do not let legacy expansion run a queried name as a child of command -pv/-pV.
                if (program(executable) == "command" && commandInfo(effective.drop(1))) continue
                val expanded = ShellCommandParser.expandExecutableSegment(effective) { nested ->
                    visit(nested, cwd, uncertainCwd, depth + 1)
                    emptyList()
                }
                var childDirectory = cwd
                var childUncertain = uncertainCwd
                for (tokens in expanded) {
                    val name = tokens.firstOrNull()?.let(::program)
                    if (name == null) { unknown("untrusted-executable-path"); break }
                    val args = tokens.drop(1)
                    when {
                        name in wrappers -> {
                            if (name == "env" && environmentHasStartup(args)) unknown("startup-environment")
                            if (name == "command") {
                                if (commandInfo(args)) break
                                // Legacy expansion also mistakes child -v/-V for metadata options.
                                if (args.any { it in setOf("-v", "-V") }) unknown("opaque-wrapper")
                            }
                            if (name == "nohup") unknown("wrapper-output") // Implicit nohup.out / HOME.
                            if ((name == "env" && args.any { it == "-S" || it.startsWith("--split-string") }) ||
                                (name == "sudo" && args.any { it in setOf("-e", "--edit", "-i", "--login", "-s", "--shell", "-R", "--chroot") })) unknown("opaque-wrapper")
                            if (name == "sudo" && expanded.last() === tokens && args.none { it in setOf("-l", "--list", "-h", "--help", "-V", "--version") }) unknown("opaque-wrapper")
                            if (name in setOf("env", "sudo") && args.any { it in setOf("-C", "-D", "--chdir") || it.startsWith("--chdir=") }) {
                                childDirectory = null; childUncertain = true; unknown("wrapper-directory")
                            }
                        }
                        name == "cd" -> {
                            // Only a shell builtin (possibly behind command) changes this shell's cwd.
                            if (tokens === expanded.first() || effective.firstOrNull()?.substringAfterLast('/') == "command") {
                                val path = args.lastOrNull()?.takeUnless { it == "-" || it.startsWith("-") }
                                cwd = path?.let { literalPath(it, cwd) }
                                uncertainCwd = cwd == null || uncertainFlow || (path?.startsWith('/') != true && uncertainCwd)
                            }
                        }
                        name in ordinary -> Unit // Printed strings are not file operands.
                        name in shells -> shell(name, args, dynamicWords, childDirectory, childUncertain)
                        name in codePrograms -> {
                            unknown("opaque-code")
                            if (name !in setOf("awk", "gawk", "mawk", "less", "more") && args.firstOrNull()?.startsWith('-') == false)
                                record(args.first(), Access.READ, childDirectory, childUncertain)
                        }
                        name == "dd" -> args.forEach { arg ->
                            if (dynamicPath(arg)) unknown("dynamic-write-operands")
                            when {
                                arg.startsWith("if=") -> record(arg.drop(3), Access.READ, childDirectory, childUncertain)
                                arg.startsWith("of=") -> record(arg.drop(3), Access.WRITE, childDirectory, childUncertain)
                                '=' !in arg -> unknown("unknown-dd-operand")
                            }
                        }
                        name in transfers -> transfer(name, args, childDirectory, childUncertain)
                        name == "sed" -> sed(args, childDirectory, childUncertain)
                        name in setOf("grep", "egrep", "fgrep", "rg") -> search(name, args, childDirectory, childUncertain)
                        name == "find" -> find(args, childDirectory, childUncertain)
                        name == "date" -> {
                            if (args.any { it == "-s" || it.startsWith("--set") || (it.startsWith("-s") && !it.startsWith("--")) || it.firstOrNull()?.isDigit() == true }) unknown("date-setting")
                            fileArguments(name, args, childDirectory, childUncertain) // Only -r/-f are files.
                        }
                        name in readers || name in writers -> {
                            if (name == "file" && options(name, args).any { it in setOf("-C", "--compile") }) unknown("opaque-code")
                            var files = fileArguments(name, args, childDirectory, childUncertain)
                            if (name in setOf("chmod", "chown", "chgrp") && args.none { it == "--reference" || it.startsWith("--reference=") }) {
                                if (files.firstOrNull()?.let(::dynamicPath) == true) unknown("dynamic-write-operands")
                                files = files.drop(1)
                            }
                            files.forEach { record(it, if (name in readers) Access.READ else Access.WRITE, childDirectory, childUncertain) }
                        }
                        else -> unknown("unknown-executable")
                    }
                }
            }
            // Substitution/group cwd cannot leak to the parent; ambiguous placement uses unknown cwd.
            val sameDirectory = cwd == initialDirectory && uncertainCwd == initialUncertain
            ShellCommandParser.inlineCommands(command).forEach {
                visit(it, if (sameDirectory) initialDirectory else null, if (sameDirectory) initialUncertain else true, depth + 1)
            }
        }

        private fun options(program: String, args: List<String>, leadingOnly: Boolean = false): Set<String> {
            val scan = scanOptions(args, valueOptions[program].orEmpty() + referenceOptions(program), optionFlags.getValue(program), leadingOnly)
            if (scan.uncertain) unknown("unknown-option-syntax")
            return scan.names
        }

        private fun commandInfo(args: List<String>) = options("command", args, leadingOnly = true).any { it in setOf("-v", "-V") }

        private fun environmentHasStartup(args: List<String>): Boolean {
            var index = 0
            var options = true
            while (index < args.size) {
                val arg = args[index++]
                when {
                    options && arg == "--" -> options = false
                    options && arg in setOf("-u", "--unset", "-C", "--chdir", "-a", "--argv0") -> index++
                    options && arg.startsWith('-') -> Unit
                    assignment.matches(arg) -> if (arg.substringBefore('=') in startupVariables) return true
                    else -> return false // Remaining arguments belong to the child, not env.
                }
            }
            return false
        }

        private fun shell(name: String, args: List<String>, dynamicWords: Set<String>, cwd: String?, uncertainCwd: Boolean) {
            // After the first script operand (or --), -c/--command are script arguments.
            // Unmodelled shell options with operands conservatively remain opaque.
            val options = if (name == "su") args else args.takeWhile { it.startsWith('-') && it != "--" }
            val flag = options.indexOfFirst { it == "--command" || (it.startsWith('-') && !it.startsWith("--") && 'c' in it.drop(1)) }
            val body = if (flag >= 0) args.getOrNull(flag + 1) else if (name == "su") args.firstOrNull { it.startsWith("--command=") }?.substringAfter('=') else null
            if (body != null) {
                if (body in dynamicWords || body.contains("\$(") || '`' in body) unknown("dynamic-shell-body")
                if (args.take(if (flag >= 0) flag + 1 else args.size).any { it in setOf("-i", "-l", "--login", "--rcfile", "--init-file") || (it.startsWith('-') && !it.startsWith("--") && it.drop(1).any { c -> c == 'i' || c == 'l' }) }) unknown("shell-startup")
            } else if (args != listOf("--help") && args != listOf("--version")) {
                unknown("opaque-shell-input")
                args.firstOrNull { !it.startsWith('-') }?.let { record(it, Access.READ, cwd, uncertainCwd) }
            }
        }

        private fun referenceOptions(program: String): Set<String> = when (program) {
                "touch" -> setOf("-r", "--reference")
                "chmod", "chown", "chgrp", "truncate" -> setOf("--reference")
                "file" -> setOf("-f", "--files-from", "-m", "--magic-file")
                "hexdump" -> setOf("-f")
                "diff" -> setOf("-X", "--exclude-from", "--from-file", "--to-file")
                "du" -> setOf("--files0-from")
                "shred" -> setOf("--random-source")
                "restorecon" -> setOf("-f")
                "setfattr" -> setOf("--restore")
                "date" -> setOf("-r", "--reference", "-f", "--file")
                else -> emptySet()
            }

        private fun fileArguments(program: String, args: List<String>, cwd: String?, uncertainCwd: Boolean, directory: (String) -> Unit = {}): List<String> {
            val references = referenceOptions(program)
            val scan = scanOptions(args, valueOptions[program].orEmpty() + references, optionFlags[program], leadingOnly = false) { arg ->
                program == "chmod" && Regex("[ugoa]*[-+=][rwxXstugo]*([,ugoa+\\-=rwxXst]*)").matches(arg)
            }
            if (scan.uncertain) unknown("unknown-option-syntax")
            for ((option, value) in scan.values) {
                if (value == null) { unknown("missing-option-operand"); continue }
                when {
                    option in references -> {
                        record(value, Access.READ, cwd, uncertainCwd)
                        if (program in setOf("restorecon", "setfattr")) unknown("opaque-file-list")
                    }
                    program in transfers && option in setOf("-t", "--target-directory") -> directory(value)
                    program in transfers && option in setOf("-S", "--suffix") && (dynamicPath(value) || '/' in value) -> unknown("backup-write-path")
                }
            }
            return scan.operands.filter { it != "-" || program !in readers }
        }

        private fun transfer(program: String, args: List<String>, cwd: String?, uncertainCwd: Boolean) {
            var destination: String? = null
            val files = fileArguments(program, args, cwd, uncertainCwd) { destination = it }
            val names = options(program, args)
            fun flag(short: Char, long: String) = "-$short" in names || long in names
            if (program == "install" && (flag('s', "--strip") || "--strip-program" in names)) unknown("transfer-execution")
            if (program == "install" && flag('d', "--directory")) {
                files.forEach { record(it, Access.WRITE, cwd, uncertainCwd) }
                destination?.let { record(it, Access.WRITE, cwd, uncertainCwd) }
                return
            }
            val implicitLink = program == "ln" && destination == null && files.size == 1
            if (destination == null && files.size < 2 && !implicitLink) { unknown("missing-write-target"); return }
            val sources = if (destination != null || implicitLink) files else files.dropLast(1)
            if (sources.isEmpty()) unknown("missing-source-operand")
            val symbolic = program == "ln" && flag('s', "--symbolic")
            // Hard links mutate the source inode's link count/ctime, even outside host roots.
            val sourceAccess = when {
                program == "mv" -> Access.WRITE
                program == "ln" || (program == "cp" && flag('l', "--link")) -> Access.READ_WRITE
                else -> Access.READ
            }
            if (!symbolic) sources.forEach {
                if (dynamicPath(it)) unknown("dynamic-write-operands")
                record(it, sourceAccess, cwd, uncertainCwd)
            }
            val output = destination ?: if (implicitLink) files.single().substringAfterLast('/').ifEmpty { "." } else files.last()
            record(output, Access.WRITE, cwd, uncertainCwd)
        }

        private fun search(program: String, args: List<String>, cwd: String?, uncertainCwd: Boolean) {
            val dataOptions = setOf("-g", "--glob", "--iglob", "-t", "--type", "-T", "--type-not", "--type-add", "--type-clear", "-A", "-B", "-C", "--after-context", "--before-context", "--context", "-m", "--max-count", "--replace", "--include", "--exclude", "--exclude-dir", "--encoding", "--sort", "--sortr", "--max-depth", "--max-filesize", "--color", "--colors", "--path-separator", "--hyperlink-format") + if (program == "rg") setOf("-r") else emptySet()
            val patternOptions = setOf("-e", "--regexp")
            val fileOptions = setOf("-f", "--file", "--ignore-file", "--exclude-from")
            val executionOptions = setOf("--pre", "--pre-glob", "--hostname-bin")
            var suppliedPattern = false
            var options = true
            var index = 0
            while (index < args.size) {
                val arg = args[index++]
                if (options && arg == "--") { options = false; continue }
                if (options && arg.startsWith('-') && arg != "-") {
                    if (program == "rg" && arg == "--files") { suppliedPattern = true; continue }
                    val valued = dataOptions + patternOptions + fileOptions + executionOptions
                    if (program == "rg" && (arg == "--search-zip" || (arg.startsWith('-') && !arg.startsWith("--") && arg.take(2) !in valued && 'z' in arg.drop(1)))) unknown("search-execution")
                    val option = if (arg.startsWith("--")) arg.substringBefore('=') else if (arg.take(2) in valued) arg.take(2) else arg
                    if (program == "rg" && option in executionOptions) unknown("search-execution")
                    if (option in valued) {
                        val attached = if (arg.startsWith("--")) arg.substringAfter('=', "").takeIf { '=' in arg } else arg.drop(2).takeIf { it.isNotEmpty() }
                        val value = attached ?: args.getOrNull(index++)
                        if (option in patternOptions || option in setOf("-f", "--file")) suppliedPattern = true
                        if (value != null && option in fileOptions) record(value, Access.READ, cwd, uncertainCwd)
                    }
                    continue
                }
                if (!suppliedPattern) suppliedPattern = true else if (arg != "-") record(arg, Access.READ, cwd, uncertainCwd)
            }
        }

        private fun sed(args: List<String>, cwd: String?, uncertainCwd: Boolean) {
            val scripts = mutableListOf<String>()
            val files = mutableListOf<String>()
            var suppliedScript = false
            var inPlace = false
            var options = true
            var index = 0
            fun backup(suffix: String) { if (suffix.any { it == '/' || it == '$' || it == '`' || it == '*' }) unknown("sed-backup-path") }
            fun script(value: String?, external: Boolean) {
                suppliedScript = true
                if (external) {
                    unknown("sed-program")
                    value?.let { record(it, Access.READ, cwd, uncertainCwd) }
                } else if (value != null) scripts.add(value) else unknown("sed-program")
            }
            while (index < args.size) {
                val arg = args[index++]
                when {
                    options && arg == "--" -> options = false
                    options && (arg == "--in-place" || arg.startsWith("--in-place=")) -> { inPlace = true; backup(arg.substringAfter('=', "")) }
                    options && arg.substringBefore('=') in setOf("--expression", "--file") -> {
                        script(if ('=' in arg) arg.substringAfter('=') else args.getOrNull(index++), arg.substringBefore('=') == "--file")
                    }
                    options && arg.startsWith('-') && !arg.startsWith("--") && arg != "-" -> {
                        var offset = 1
                        while (offset < arg.length) {
                            when (val option = arg[offset++]) {
                                'i' -> { inPlace = true; backup(arg.substring(offset)); break }
                                'e', 'f' -> { script(arg.substring(offset).ifEmpty { args.getOrNull(index++).orEmpty() }, option == 'f'); break }
                                'n', 'E', 'r', 'u', 's', 'z' -> Unit
                                else -> unknown("sed-options")
                            }
                        }
                    }
                    options && arg.startsWith("--") -> if (arg !in setOf("--quiet", "--silent", "--regexp-extended", "--unbuffered", "--separate", "--null-data", "--sandbox", "--help", "--version")) unknown("sed-options")
                    !suppliedScript -> { suppliedScript = true; scripts.add(arg) }
                    else -> files.add(arg)
                }
            }
            if (!suppliedScript || scripts.any { !safeSedScript(it) }) unknown("sed-program")
            files.filter { it != "-" }.forEach { record(it, if (inPlace) Access.WRITE else Access.READ, cwd, uncertainCwd) }
        }

        private fun find(args: List<String>, cwd: String?, uncertainCwd: Boolean) {
            val roots = mutableListOf<String>()
            val values = setOf("-name", "-iname", "-path", "-ipath", "-wholename", "-iwholename", "-regex", "-iregex", "-type", "-xtype", "-maxdepth", "-mindepth", "-printf", "-size", "-user", "-group", "-uid", "-gid", "-perm", "-links", "-inum", "-mtime", "-mmin", "-atime", "-amin", "-ctime", "-cmin", "-regextype", "-context", "-D")
            val safe = setOf("-H", "-L", "-P", "-print", "-print0", "-ls", "-prune", "-quit", "-true", "-false", "-empty", "-readable", "-writable", "-executable", "-xdev", "-mount", "-depth", "-daystart", "-noleaf", "-ignore_readdir_race", "-noignore_readdir_race", "-a", "-and", "-o", "-or", "-not", "!", "(", ")", ",")
            var expression = false
            var delete = false
            var indirectRoots = false
            var index = 0
            while (index < args.size) {
                val arg = args[index++]
                when {
                    arg in setOf("-H", "-L", "-P") && !expression -> Unit
                    !expression && arg == "--" -> Unit
                    !expression && !arg.startsWith('-') && arg !in setOf("!", "(", ")") -> roots.add(arg)
                    else -> {
                        expression = true
                        when {
                            arg == "-delete" -> delete = true
                            arg in setOf("-exec", "-execdir", "-ok", "-okdir") -> {
                                unknown("find-execution")
                                while (index < args.size && args[index] !in setOf(";", "+")) index++
                                if (index < args.size) index++
                            }
                            arg in setOf("-fprint", "-fprint0", "-fprintf", "-fls") -> {
                                args.getOrNull(index++)?.let { record(it, Access.WRITE, cwd, uncertainCwd) }
                                if (arg == "-fprintf") index++ // Format is data, never a file operand.
                            }
                            arg == "-files0-from" || arg in setOf("-newer", "-anewer", "-cnewer", "-samefile") -> {
                                if (arg == "-files0-from") indirectRoots = true
                                args.getOrNull(index++)?.let { record(it, Access.READ, cwd, uncertainCwd) }
                            }
                            arg in values -> index++
                            arg in safe || arg.matches(Regex("-O[0-3]")) -> Unit
                            else -> unknown("find-expression")
                        }
                    }
                }
            }
            if (delete && indirectRoots) unknown("dynamic-write-path")
            if (roots.isEmpty() && !indirectRoots) roots.add(".")
            roots.forEach { record(it, if (delete) Access.WRITE else Access.READ, cwd, uncertainCwd) }
        }
    }

    /** Finite sed subset: addresses, substitutions without w/e, and non-file/non-exec commands. */
    private fun safeSedScript(script: String): Boolean {
        var index = 0
        fun delimited(delimiter: Char): Boolean {
            index++
            while (index < script.length) {
                val char = script[index++]
                if (char == '\\') { if (index >= script.length) return false; index++ }
                else if (char == delimiter) return true
            }
            return false
        }
        while (index < script.length) {
            while (index < script.length && (script[index].isWhitespace() || script[index] == ';')) index++
            if (index == script.length) break
            if (script[index] == '#') { while (index < script.length && script[index] != '\n') index++; continue }
            while (index < script.length) {
                val char = script[index]
                if (char == '/') { if (!delimited('/')) return false }
                else if (char.isDigit() || char in "$,~! \t") index++ else break
            }
            if (index >= script.length) return false
            when (script[index++]) {
                's' -> {
                    val delimiter = script.getOrNull(index)?.takeUnless { it.isWhitespace() || it == '\\' } ?: return false
                    if (!delimited(delimiter)) return false
                    // Start at the replacement (which has no opening delimiter).
                    index--
                    if (!delimited(delimiter)) return false
                    while (index < script.length && script[index] != ';' && script[index] != '\n') {
                        val flag = script[index++]
                        if (!flag.isDigit() && flag !in "gpIiMm \t") return false
                    }
                }
                'p', 'P', 'd', 'D', 'q', 'Q', 'n', 'N', 'h', 'H', 'g', 'G', 'x', 'z', '=' -> {
                    while (index < script.length && script[index].isWhitespace() && script[index] != '\n') index++
                    if (index < script.length && script[index] != ';' && script[index] != '\n') return false
                }
                else -> return false
            }
        }
        return true
    }

    private fun program(executable: String): String? {
        if (executable.isBlank() || executable.any { it in "$`*?[{~" }) return null
        return if ('/' !in executable || executable.substringBeforeLast('/') in executableDirectories) executable.substringAfterLast('/') else null
    }

    private fun target(path: String, access: Access, namespace: Namespace): String? {
        if (path in harmlessDevices) return null
        val special = interfaces.any { below(path, it) }
        val system = namespace == Namespace.HOST && (hostRoots.any { below(path, it) } ||
            (access != Access.READ && path in setOf("/", "/*", "/data", "/data/*")))
        return if (special || system) "${access.name}:$path" else null
    }

    private fun below(path: String, root: String): Boolean = path == root || path.startsWith("$root/") ||
        path.startsWith("$root*") || path.startsWith("$root?")

    private fun dynamicPath(raw: String) = raw.any { it in "$`*?[{~" }

    private fun workspacePath(raw: String): Boolean = (raw == "~/workspace" || raw.startsWith("~/workspace/")) &&
        below(normalize("/workspace/" + raw.removePrefix("~/workspace")), "/workspace")

    private fun literalPath(raw: String, cwd: String?): String? {
        if (raw.isBlank() || raw.any { it == '$' || it == '`' } || raw.startsWith('~')) return null
        return normalize(if (raw.startsWith('/')) raw else cwd?.let { "$it/$raw" } ?: return null)
    }

    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> { if (parts.isNotEmpty()) parts.removeLast(); Unit }
                else -> parts.addLast(it)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun immutable(values: Collection<String>): Set<String> = Collections.unmodifiableSet(LinkedHashSet(values))
}
