package com.aicode.feature.agent.domain.permission

/**
 * shell 命令的安全静态分析器——授权系统的安全核心。
 *
 * 职责：把一条命令行拆成「顶层段」并分词，同时判断这条命令是否**可被静态判定**。
 * 解析结果供禁止规则匹配、高危删除保护与显式 root 命令审批使用。
 *
 * 安全约定（务必维持）：
 * - 引号感知：单/双引号内的 `&& || | ;` 等不视为分隔符，不会被错误拆段。
 * - 不可静态判定（[Analysis.analyzable] = false）的触发条件：
 *     · 命令替换 `$(...)`、反引号 `` `...` ``（双引号内仍生效，单引号内不生效）；
 *     · 子shell/分组括号 `(` `)`、进程替换 `<(` `>(`（均以裸 `(` 命中）；
 *     · 输出重定向到**绝对路径**（精确字面量 `/dev/null` 除外）、动态目标或无法识别的重定向；
 *     · 未闭合的引号。
 *   相对重定向（落在 cwd=工作区内）视为安全。输入重定向 `<` 不触发（读取风险较低）。
 * - 匹配按**字面首 token**：`/usr/bin/node` 不会被 `node` 规则命中（宁可多问，不误放）。
 * - 段首的环境赋值（`FOO=bar cmd`）在取程序名/匹配时被跳过，使 `FOO=bar git ...` 仍按 `git` 处理。
 */
object ShellCommandParser {

    enum class BlockerKind {
        COMMAND_SUBSTITUTION,
        BACKTICK_SUBSTITUTION,
        SUBSHELL_OR_GROUP,
        ABSOLUTE_OUTPUT_REDIRECT,
        DYNAMIC_OUTPUT_REDIRECT,
        UNKNOWN_OUTPUT_REDIRECT,
        DYNAMIC_ARGUMENT,
        MISSING_REDIRECT_TARGET,
        UNKNOWN_FILE_DESCRIPTOR,
        COMPOUND_COMMAND,
        UNTERMINATED_ESCAPE,
        UNTERMINATED_QUOTE
    }

    data class Blocker(val kind: BlockerKind, val detail: String? = null)

    /** Lexical evidence only: no expansion, filesystem access, or claim of sandboxing. */
    data class Redirection(
        val operator: String,
        val target: String,
        val literal: Boolean,
        val hereDocumentBody: String? = null
    )
    data class CommandSegment(val tokens: List<String>, val redirections: List<Redirection>)

    /**
     * @param segments 顶层每一段的 token 列表（已去引号、按空白分词；重定向及其目标不入 token）。
     * @param analyzable 是否可静态判定；false 时需结合命令的破坏性判断是否触发安全防护。
     * @param blockers 触发「不可静态判定」的具体构造（如 `命令替换 $(...)`、`子 shell 括号`）。
     *   供授权弹窗与拦截原因回显：不告诉 AI 是哪一段出的问题，它只能反复整条重试。
     * @param hasFileOutputRedirect 存在文件写入或未能确认安全的输出重定向；丢弃输出与 fd 复制不计入。
     */
    data class Analysis(
        val segments: List<List<String>>,
        val analyzable: Boolean,
        val blockers: List<Blocker> = emptyList(),
        val hasFileOutputRedirect: Boolean = false,
        val commandSegments: List<CommandSegment> = emptyList()
    )

    private const val NONE = 0
    private const val SINGLE = 1
    private const val DOUBLE = 2

    /** 段首环境赋值形如 `NAME=value`。 */
    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    fun analyze(command: String): Analysis = analyzeShell(command)

    private fun analyzeShell(commandText: String): Analysis {
        val hereData = hereDocumentView(commandText)
        val command = hereData.shell
        val segments = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var redirects = mutableListOf<Redirection>()
        val commandSegments = mutableListOf<CommandSegment>()
        val token = StringBuilder()
        var tokenStarted = false
        var tokenQuoted = false
        var tokenDynamic = false
        var tokenParameterExpansion = false
        var hasFileOutputRedirect = false
        // 触发「不可静态判定」的构造清单：去重后随 Analysis 返回，供上层解释拦截原因。
        val blockers = mutableListOf<Blocker>()
        fun block(kind: BlockerKind, detail: String? = null) {
            val reason = Blocker(kind, detail)
            if (reason !in blockers) blockers.add(reason)
        }
        var quote = NONE
        var redirectOperator: String? = null
        var redirectDescriptor: String? = null
        var redirectOffset = -1
        var i = 0
        val n = command.length

        fun flushToken() {
            if (tokenStarted) {
                val t = token.toString()
                val operator = redirectOperator
                if (operator != null) {
                    redirects.add(Redirection(operator, t, !tokenDynamic, hereData.bodies[redirectOffset]))
                    if ('>' in operator) {
                        val descriptorTarget = t.removeSuffix("-")
                        val duplicatesDescriptor = operator == ">&" && !tokenDynamic && quote == NONE &&
                            (t == "-" || (descriptorTarget.isNotEmpty() && descriptorTarget.all { it in '0'..'9' }))
                        val fileTarget = operator != ">&" || redirectDescriptor == null
                        val discardsOutput = fileTarget && !tokenDynamic && quote == NONE && t == "/dev/null"
                        if (!duplicatesDescriptor && !discardsOutput) {
                            hasFileOutputRedirect = true
                            when {
                                tokenDynamic -> block(BlockerKind.DYNAMIC_OUTPUT_REDIRECT, t)
                                !fileTarget || t.isEmpty() -> block(BlockerKind.UNKNOWN_OUTPUT_REDIRECT, "$operator$t")
                                t.startsWith("/") -> block(BlockerKind.ABSOLUTE_OUTPUT_REDIRECT, t)
                            }
                        }
                    }
                    redirectOperator = null
                    redirectDescriptor = null
                } else {
                    if (tokenParameterExpansion) block(BlockerKind.DYNAMIC_ARGUMENT, t)
                    current.add(t)
                }
                token.setLength(0)
                tokenStarted = false
                tokenQuoted = false
                tokenDynamic = false
                tokenParameterExpansion = false
            }
        }
        fun finishRedirect() {
            val operator = redirectOperator ?: return
            block(BlockerKind.MISSING_REDIRECT_TARGET, operator)
            if ('>' in operator) hasFileOutputRedirect = true
            redirectOperator = null
            redirectDescriptor = null
        }
        fun startRedirect(operator: String) {
            if ('>' in operator && tokenStarted && !tokenQuoted && token.startsWith("{") && token.endsWith("}")) {
                block(BlockerKind.UNKNOWN_FILE_DESCRIPTOR, token.toString())
                hasFileOutputRedirect = true
            }
            val descriptor = token.toString().takeIf {
                !operator.startsWith('&') && redirectOperator == null && tokenStarted && !tokenQuoted &&
                    it.isNotEmpty() && it.all { c -> c in '0'..'9' }
            }
            if (descriptor != null) {
                token.setLength(0)
                tokenStarted = false
            } else {
                flushToken()
            }
            finishRedirect()
            redirectOperator = operator
            redirectDescriptor = descriptor
            redirectOffset = i
        }
        fun flushSegment() {
            flushToken()
            finishRedirect()
            if (current.isNotEmpty() || redirects.isNotEmpty()) {
                commandSegments.add(CommandSegment(current.toList(), redirects.toList()))
                redirects = mutableListOf()
            }
            if (current.isNotEmpty()) {
                if (programPrefix(current) in setOf(
                    "{", "}", "if", "then", "elif", "else", "fi", "while", "until",
                    "for", "select", "do", "done", "case", "esac", "!"
                )) {
                    block(BlockerKind.COMPOUND_COMMAND)
                }
                segments.add(current)
                current = mutableListOf()
            }
        }
        fun consumeSubstitution(start: Int, kind: BlockerKind) {
            block(kind)
            val end = inlineCommandEnd(command, start)?.plus(1) ?: n
            token.append(command, i, end)
            tokenStarted = true
            tokenDynamic = true
            i = end
        }

        while (i < n) {
            val c = command[i]
            when (quote) {
                SINGLE -> {
                    if (c == '\'') quote = NONE else { token.append(c); tokenStarted = true }
                    i++
                }
                DOUBLE -> {
                    when {
                        c == '"' -> { quote = NONE; tokenStarted = true; i++ }
                        c == '\\' && i + 1 < n && command[i + 1] in "\$`\"\\\n" -> {
                            if (command[i + 1] != '\n') token.append(command[i + 1])
                            i += 2
                        }
                        // 双引号内命令替换/反引号仍会执行；内部语法独立递归分析。
                        c == '`' -> consumeSubstitution(i, BlockerKind.BACKTICK_SUBSTITUTION)
                        c == '$' && i + 1 < n && command[i + 1] == '(' ->
                            consumeSubstitution(i + 1, BlockerKind.COMMAND_SUBSTITUTION)
                        else -> {
                            if (c == '$') { tokenDynamic = true; tokenParameterExpansion = true }
                            token.append(c); tokenStarted = true; i++
                        }
                    }
                }
                else -> { // 引号外
                    when {
                        c == '\'' -> { quote = SINGLE; tokenStarted = true; tokenQuoted = true; i++ }
                        c == '"' -> { quote = DOUBLE; tokenStarted = true; tokenQuoted = true; i++ }
                        c == '\\' -> {
                            // 行末续行 `\<newline>`：整体跳过；其余转义取下一字符字面值。
                            if (i + 1 < n) {
                                val nx = command[i + 1]
                                if (nx != '\n') { token.append(nx); tokenStarted = true; tokenQuoted = true }
                                i += 2
                            } else {
                                block(BlockerKind.UNTERMINATED_ESCAPE)
                                tokenDynamic = true
                                i++
                            }
                        }
                        c == '$' && i + 1 < n && command[i + 1] == '(' ->
                            consumeSubstitution(i + 1, BlockerKind.COMMAND_SUBSTITUTION)
                        c == '`' -> consumeSubstitution(i, BlockerKind.BACKTICK_SUBSTITUTION)
                        c == '(' || c == ')' -> {
                            block(BlockerKind.SUBSHELL_OR_GROUP)
                            flushSegment()
                            i = if (c == '(') inlineCommandEnd(command, i)?.plus(1) ?: n else i + 1
                        }
                        // 顶层段分隔符。
                        c == '\n' || c == ';' -> { flushSegment(); i++ }
                        c == '&' && i + 1 < n && command[i + 1] == '>' -> {
                            val operator = if (i + 2 < n && command[i + 2] == '>') "&>>" else "&>"
                            startRedirect(operator)
                            i += operator.length
                        }
                        c == '&' -> { flushSegment(); i += if (i + 1 < n && command[i + 1] == '&') 2 else 1 }
                        c == '|' -> { flushSegment(); i += if (i + 1 < n && command[i + 1] == '|') 2 else 1 }
                        c == '>' || c == '<' -> {
                            val next = command.getOrNull(i + 1)
                            val operator = when {
                                c == '<' && next == '<' && command.getOrNull(i + 2) == '<' -> "<<<"
                                c == '<' && next == '<' && command.getOrNull(i + 2) == '-' -> "<<-"
                                c == '>' && next in listOf('>', '&', '|') -> ">$next"
                                c == '<' && next in listOf('>', '&', '<') -> "<$next"
                                else -> c.toString()
                            }
                            startRedirect(operator)
                            i += operator.length
                        }
                        c.isWhitespace() -> { flushToken(); i++ }
                        else -> {
                            if (c == '$' || c == '`' || c == '*' || c == '?' || c == '[' || c == '{' || (c == '~' && !tokenStarted)) {
                                tokenDynamic = true
                            }
                            if (c == '$') tokenParameterExpansion = true
                            token.append(c); tokenStarted = true; i++
                        }
                    }
                }
            }
        }
        if (quote != NONE) block(BlockerKind.UNTERMINATED_QUOTE) // 未闭合引号：不可信
        flushSegment()
        val nestedCommands = inlineCommands(commandText).toMutableList()
        segments.forEach { segment ->
            expandExecutableSegment(segment) { nested ->
                nestedCommands.add(nested)
                emptyList()
            }
        }
        for (nested in nestedCommands) {
            val analysis = analyze(nested)
            analysis.blockers.forEach { block(it.kind, it.detail) }
            hasFileOutputRedirect = hasFileOutputRedirect || analysis.hasFileOutputRedirect
        }
        return Analysis(segments, blockers.isEmpty(), blockers, hasFileOutputRedirect, commandSegments)
    }

    /** 跳过段首环境赋值后的有效 token（用于取程序名与匹配）。 */
    internal fun effectiveTokens(tokens: List<String>): List<String> {
        var idx = 0
        while (idx < tokens.size && ENV_ASSIGN.matches(tokens[idx])) idx++
        return if (idx == 0) tokens else tokens.subList(idx, tokens.size)
    }

    /** 该段默认记忆的命令前缀 = 程序名 token；空段返回 null。 */
    fun programPrefix(tokens: List<String>): String? = effectiveTokens(tokens).firstOrNull()

    fun requiresRootApproval(command: String): Boolean =
        executableSegments(command).any { programPrefix(it)?.substringAfterLast('/') in setOf("sudo", "su") }

    fun executableSegments(command: String): List<List<String>> = buildList {
        analyze(command).segments.forEach { addAll(expandExecutableSegment(it)) }
        inlineCommands(command).forEach { addAll(executableSegments(it)) }
    }

    internal fun expandExecutableSegment(
        tokens: List<String>,
        parseCommand: (String) -> List<List<String>> = ::executableSegments
    ): List<List<String>> = buildList {
        val effective = effectiveTokens(tokens)
        val program = effective.firstOrNull()?.substringAfterLast('/') ?: return@buildList
        add(effective)
        when (program) {
            "{", "if", "then", "elif", "else", "while", "until", "do", "!" -> {
                addAll(expandExecutableSegment(effective.drop(1), parseCommand))
            }
            "command", "exec", "nohup", "busybox", "toybox", "sudo" -> {
                if (program == "command" && effective.drop(1).any { it in setOf("-v", "-V") }) return@buildList
                var index = 1
                while (index < effective.size && effective[index].startsWith("-")) {
                    val option = effective[index]
                    if (option == "--") {
                        index++
                        break
                    }
                    val takesValue = (program == "exec" && option == "-a") ||
                        (program == "sudo" && option in setOf("-u", "-g", "-h", "-p", "-C", "-r", "-t", "--user", "--group", "--host", "--prompt", "--close-from", "--role", "--type", "--chdir"))
                    index += if (takesValue) 2 else 1
                }
                addAll(expandExecutableSegment(effective.drop(index), parseCommand))
            }
            "env" -> {
                var index = 1
                while (index < effective.size) {
                    val token = effective[index]
                    when {
                        token == "--" -> {
                            index++
                            break
                        }
                        token == "-S" || token == "--split-string" -> {
                            addAll(parseCommand(effective.drop(index + 1).joinToString(" ")))
                            return@buildList
                        }
                        token.startsWith("--split-string=") -> {
                            addAll(parseCommand(token.substringAfter('=')))
                            return@buildList
                        }
                        token in setOf("-u", "--unset", "-C", "--chdir", "-a", "--argv0") -> index += 2
                        token.startsWith("-") || ENV_ASSIGN.matches(token) -> index++
                        else -> break
                    }
                }
                addAll(expandExecutableSegment(effective.drop(index), parseCommand))
            }
            "su", "sh", "bash", "dash", "ash", "zsh", "ksh" -> {
                val commandFlag = effective.indexOfFirst {
                    it == "--command" || (it.startsWith("-") && !it.startsWith("--") && 'c' in it.drop(1))
                }
                if (commandFlag >= 1) {
                    effective.getOrNull(commandFlag + 1)?.let { addAll(parseCommand(it)) }
                }
                if (program == "su") {
                    effective.firstOrNull { it.startsWith("--command=") }
                        ?.substringAfter('=')?.let { addAll(parseCommand(it)) }
                }
            }
        }
    }

    // All consumers (including DENY matching) share the same here-data boundaries.
    internal fun inlineCommands(command: String): List<String> {
        val view = hereDocumentView(command)
        return inlineCommandsInShell(view.shell) + view.expansions
    }

    private data class HereDocument(val delimiter: String, val quoted: Boolean, val stripTabs: Boolean, val redirectOffset: Int)
    // Masking preserves offsets, so each body stays attached to its own redirect even across
    // multiple segments/documents. Bodies never become executable segments or DENY tokens.
    private data class HereDataView(val shell: String, val expansions: List<String>, val bodies: Map<Int, String> = emptyMap())

    /** Mask data, not commands. No execution/expansion; unknown syntax remains best-effort. */
    private fun hereDocumentView(command: String): HereDataView {
        if (!command.contains("<<")) return HereDataView(command, emptyList())
        val shell = command.toCharArray()
        val expansions = mutableListOf<String>()
        val bodies = linkedMapOf<Int, String>()
        val pending = ArrayDeque<HereDocument>()
        var quote = NONE
        var index = 0
        while (index < command.length) {
            val c = command[index]
            if (quote == SINGLE) {
                if (c == '\'') quote = NONE
                index++
                continue
            }
            if (c == '\\') { index += 2; continue }
            if (c == '\'' && quote == NONE) { quote = SINGLE; index++; continue }
            if (c == '"') { quote = if (quote == DOUBLE) NONE else DOUBLE; index++; continue }
            if (c == '`' || (c == '(' && (quote == NONE || command.getOrNull(index - 1) == '$'))) {
                // Nested shell syntax is handled by the existing recursive command parser.
                index = inlineCommandEnd(command, index)?.plus(1) ?: command.length
                continue
            }
            if (quote != NONE) { index++; continue }
            if (c == '#' && (index == 0 || command[index - 1].isWhitespace() || command[index - 1] in ";|&()")) {
                index = command.indexOf('\n', index).takeIf { it >= 0 } ?: command.length
                continue
            }
            if (command.startsWith("<<<", index)) { index += 3; continue }
            if (command.startsWith("<<", index)) {
                var cursor = index + 2
                val stripTabs = command.getOrNull(cursor) == '-'
                if (stripTabs) cursor++
                while (command.getOrNull(cursor) in listOf(' ', '\t')) cursor++
                val delimiter = StringBuilder()
                var delimiterQuote = NONE
                var quoted = false
                var started = false
                while (cursor < command.length) {
                    val word = command[cursor]
                    if (delimiterQuote == NONE && (word.isWhitespace() || word in ";|&()<>")) break
                    started = true
                    when {
                        word == '\\' && delimiterQuote != SINGLE && cursor + 1 < command.length -> {
                            quoted = true; delimiter.append(command[cursor + 1]); cursor += 2; continue
                        }
                        word == '\'' && delimiterQuote != DOUBLE -> {
                            quoted = true; delimiterQuote = if (delimiterQuote == SINGLE) NONE else SINGLE
                        }
                        word == '"' && delimiterQuote != SINGLE -> {
                            quoted = true; delimiterQuote = if (delimiterQuote == DOUBLE) NONE else DOUBLE
                        }
                        else -> delimiter.append(word)
                    }
                    cursor++
                }
                if (started && delimiterQuote == NONE) pending.addLast(HereDocument(delimiter.toString(), quoted, stripTabs, index))
                index = maxOf(cursor, index + 2)
                continue
            }
            if (c == '\n' && pending.isNotEmpty()) {
                index++
                while (pending.isNotEmpty()) {
                    val here = pending.removeFirst()
                    val bodyStart = index
                    var bodyEnd = command.length
                    while (index < command.length) {
                        val lineEnd = command.indexOf('\n', index).takeIf { it >= 0 } ?: command.length
                        val line = command.substring(index, lineEnd)
                        val delimiterLine = if (here.stripTabs) line.trimStart('\t') else line
                        val next = if (lineEnd < command.length) lineEnd + 1 else lineEnd
                        if (delimiterLine == here.delimiter) { bodyEnd = index; index = next; break }
                        index = next
                    }
                    val body = command.substring(bodyStart, bodyEnd)
                    bodies[here.redirectOffset] = body
                    if (!here.quoted) expansions.addAll(hereDataCommands(body))
                    for (position in bodyStart until index) if (shell[position] != '\n') shell[position] = ' '
                }
                continue
            }
            index++
        }
        return HereDataView(String(shell), expansions, bodies)
    }

    /** Quotes in an unquoted here-doc body are data; only backslash escapes suppress expansion. */
    private fun hereDataCommands(data: String): List<String> = buildList {
        var index = 0
        while (index < data.length) {
            if (data[index] == '\\' && data.getOrNull(index + 1) in listOf('$', '`', '\\', '\n')) {
                index += 2
                continue
            }
            val start = when {
                data[index] == '`' -> index
                data[index] == '$' && data.getOrNull(index + 1) == '(' -> index + 1
                else -> { index++; continue }
            }
            val end = inlineCommandEnd(data, start) ?: break
            add(data.substring(start + 1, end))
            index = end + 1
        }
    }

    // 单引号中的内容是字面数据；命令替换、反引号和分组中的内容才需递归检查。
    private fun inlineCommandsInShell(command: String): List<String> = buildList {
        var quote = NONE
        var index = 0
        while (index < command.length) {
            val c = command[index]
            if (c == '\\' && quote != SINGLE) {
                index += 2
                continue
            }
            if (quote == SINGLE) {
                if (c == '\'') quote = NONE
                index++
                continue
            }
            if (c == '\'' && quote == NONE) {
                quote = SINGLE
            } else if (c == '"') {
                quote = if (quote == DOUBLE) NONE else DOUBLE
            } else if (c == '`' || (c == '(' && (quote == NONE || (index > 0 && command[index - 1] == '$')))) {
                val end = inlineCommandEnd(command, index) ?: break
                add(command.substring(index + 1, end))
                index = end + 1
                continue
            }
            index++
        }
    }

    private fun inlineCommandEnd(command: String, start: Int): Int? {
        var quote = NONE
        var index = start + 1
        while (index < command.length) {
            val c = command[index]
            if (c == '\\' && quote != SINGLE) {
                index += 2
                continue
            }
            if (command[start] == '`') {
                if (c == '`') return index
            } else {
                when {
                    c == '\'' && quote != DOUBLE -> quote = if (quote == SINGLE) NONE else SINGLE
                    c == '"' && quote != SINGLE -> quote = if (quote == DOUBLE) NONE else DOUBLE
                    quote != SINGLE && (c == '`' || (c == '(' && (quote == NONE || command.getOrNull(index - 1) == '$'))) -> {
                        index = inlineCommandEnd(command, index) ?: return null
                    }
                    c == ')' && quote == NONE -> return index
                }
            }
            index++
        }
        return null
    }

    /**
     * 已知的「子命令分发器」程序：其第一个非 flag 参数是子命令（`git pull`/`npm install`/`cargo build`）。
     * 对这类程序，「始终允许」应连子命令一起记忆，避免仅记 `git` 就把 `git push`/`git reset --hard` 等
     * 一并放行。非分发器程序仍按程序名整体记忆（`cat`/`ls` 等）。
     */
    private val SUBCOMMAND_DISPATCHERS = setOf(
        "git", "gh",
        "npm", "npx", "yarn", "pnpm", "pnpx",
        "cargo", "rustup",
        "go",
        "pip", "pip3", "poetry", "uv", "conda",
        "gradle", "mvn", "sbt",
        "docker", "kubectl", "helm",
        "adb", "fastlane",
        "brew", "apt", "apt-get", "gem", "bundle",
        "az", "aws", "gcloud", "terraform", "ansible"
    )

    data class RmInfo(
        val isRm: Boolean,
        val isRecursive: Boolean,
        val isWildcard: Boolean,
        val targetPaths: List<String>
    )

    /** 解析该命令段是否为 rm 删除操作及其参数信息（是否递归、通配以及操作目标路径）。 */
    fun parseRmInfo(tokens: List<String>): RmInfo {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull()
        if (program != "rm" && program?.endsWith("/rm") != true) {
            return RmInfo(isRm = false, isRecursive = false, isWildcard = false, targetPaths = emptyList())
        }
        var isRecursive = false
        var isWildcard = false
        val targetPaths = mutableListOf<String>()
        var hasDoubleDash = false
        for (i in 1 until eff.size) {
            val t = eff[i]
            if (!hasDoubleDash && t == "--") {
                hasDoubleDash = true
                continue
            }
            if (!hasDoubleDash && t.startsWith("-") && t != "-") {
                if (t == "--recursive" || (!t.startsWith("--") && (t.contains('r') || t.contains('R')))) {
                    isRecursive = true
                }
                continue
            }
            if (t.contains('*') || t.contains('?')) {
                isWildcard = true
            }
            targetPaths.add(t)
        }
        return RmInfo(isRm = true, isRecursive = isRecursive, isWildcard = isWildcard, targetPaths = targetPaths)
    }

    /**
     * 「始终允许」可记忆的命令前缀：
     * - rm 命令精细处理：递归/通配符删除不可记忆；普通单文件删除精细记忆为完整命令前缀；
     * - 非分发器程序：仅程序名（`cat`/`ls`），与 [programPrefix] 一致；
     * - 子命令分发器：程序名 + 紧随其后的第一个非 flag 子命令（`git pull`/`git clone`/`npm install`），
     *   程序名后紧跟 flag（如 `git --version`）时退化为仅程序名。空段返回 null。
     *
     * 匹配仍走 [matches] 的 token 前缀语义：`git pull` 命中 `git pull origin main`，但不命中 `git clone`，
     * 故不同子命令各自独立记忆、互不串放。
     */
    fun rememberablePrefix(tokens: List<String>): String? {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull() ?: return null
        val rmInfo = parseRmInfo(tokens)
        if (rmInfo.isRm) {
            if (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard) {
                return null
            }
            return eff.joinToString(" ")
        }
        if (program !in SUBCOMMAND_DISPATCHERS) return program
        val subcommand = eff.getOrNull(1)?.takeIf { !it.startsWith("-") } ?: return program
        return "$program $subcommand"
    }

    /** token 前缀匹配：规则 [pattern] 的 token 序列是该段有效 token 序列的前缀即命中。 */
    fun matches(pattern: String, segmentTokens: List<String>): Boolean {
        val patternTokens = pattern.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (patternTokens.isEmpty()) return false
        val eff = effectiveTokens(segmentTokens)
        if (eff.size < patternTokens.size) return false
        for (k in patternTokens.indices) if (eff[k] != patternTokens[k]) return false
        return true
    }
}
