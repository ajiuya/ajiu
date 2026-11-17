package com.aicode.feature.agent.domain.memory

internal object ProjectMemoryContentPolicy {
    private val sensitive = Regex("(?i)(password|passwd|secret|token|credential|api.?key|private.?key|bearer|密码|密钥|凭据|口令|https?://|-----BEGIN|[A-Za-z0-9+/=_-]{32,})")
    private val forbiddenReport = Regex(
        "(?im)(authorization\\s*[:=]|bearer\\s+\\S+|(?:password|passwd|api[_-]?key|access[_-]?token|token|secret)\\s*[:=]\\s*\\S+|" +
            "-----BEGIN|https?://[^\\s/]*@|(?:privateReasoning|reasoning_content|thinkingBlocksJson)\\s*[:=]|" +
            "</?(?:think|analysis|private_reasoning)>|^\\s*(?:[$#]\\s+|```(?:bash|sh|shell|console)|stdout\\s*:|stderr\\s*:)|" +
            "^\\s*(?:sudo |su -|curl |wget |ssh |git (?:push|clone)|(?:\\./)?gradlew |npm (?:run|install)|python[23]? [^:]+\\.py))"
    )

    fun safeText(text: String): String? = text.trim().takeIf {
        it.isNotEmpty() && it.length <= 160 && !sensitive.containsMatchIn(it) &&
            it.none { char -> char.isISOControl() || char in "`$<>\\" }
    }

    fun safePath(path: String): Boolean = path.isNotEmpty() && path.length <= 240 && !path.startsWith('/') &&
        path.split('/').none { it.isEmpty() || it == ".." || it.startsWith('.') } &&
        !sensitive.containsMatchIn(path) && path.none { it.isISOControl() || it in "`$<>\\" }

    fun validateReport(summary: String, body: String) {
        require(!forbiddenReport.containsMatchIn(summary) && !forbiddenReport.containsMatchIn(body)) {
            "Project history accepts bounded factual reports, not credentials, private reasoning, commands or logs"
        }
        require(summary.none { it.isISOControl() } && body.none { it.isISOControl() && it != '\n' && it != '\t' })
    }
}
