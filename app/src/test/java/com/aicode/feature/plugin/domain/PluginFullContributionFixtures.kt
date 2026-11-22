package com.aicode.feature.plugin.domain

internal object PluginFullContributionFixtures {
    val runtime = """{"type":"javascript","entry":"runtime/main.mjs","command":["node","--no-warnings","runtime/main.mjs"],"resident":true,"dependencies":{"tool":"npm","file":"package.json"}}"""
    val contributions = """
        "skills":[{"id":"hello","file":"skills/hello.md"}],
        "agents":[{"id":"worker","file":"agents/worker.md"}],
        "workspaceTools":[{"id":"work","title":"Work","pageFile":"pages/work.html"}],
        "uiEntries":[{"id":"menu","title":"Menu","slot":"chat.header.menu","handler":"menu","confirm":true},
            {"id":"backupOption","title":"Extra backup","slot":"backup.data"}],
        "tools":[{"id":"task","title":"Task","inputSchema":"schema.json","handler":"task"}],
        "hooks":[{"id":"run","event":"agent.run.started","handler":"run"},
            {"id":"before","event":"agent.tool.before","handler":"before","mode":"transform"},
            {"id":"after","event":"agent.tool.after","handler":"after","mode":"transform"},
            {"id":"http","event":"agent.http.before","handler":"http","mode":"transform"},
            {"id":"startup","event":"app.started","handler":"startup"},
            {"id":"installed","event":"plugin.installed","handler":"installed"},
            {"id":"disabled","event":"plugin.disabled","handler":"disabled"},
            {"id":"uninstalled","event":"plugin.uninstalled","handler":"uninstalled"},
            {"id":"export","event":"backup.export.before","handler":"export"},
            {"id":"import","event":"backup.import.finished","handler":"import"}],
        "mcp":[{"id":"connections","file":"mcp.json"}],
        "providers":[{"id":"model","name":"Model service","format":"openai","baseUrl":"http://127.0.0.1:8123/v1/"}],
        "sessionSources":[{"id":"sessions","title":"Sessions","handlers":{"listSessions":"list","readSession":"read",
            "sendMessage":"send","interruptSession":"interrupt","createSession":"create","refresh":"refresh"}}],
        "shizuku":{"purpose":"Read device status"},"backup":{"purpose":"Export selected plugin data"},
        "modelAccess":{"purpose":"Summarize text using a configured model"},
        "overlay":{"purpose":"Show a floating status panel","surfaces":[{"id":"status","page":"pages/overlay.html","width":240,"height":320,"snap":"x"}]},
        "configPage":"pages/config.html"
    """.trimIndent()
    fun manifest() = PluginCoreFixtures.manifest(contributions, runtime)
    fun files() = linkedMapOf(
        "runtime/main.mjs" to "throw new Error('must not execute during validation')",
        "package.json" to """{"name":"fixture","version":"1.0.0"}""",
        "package-lock.json" to "{}",
        "agents/worker.md" to "---\nname: Root\ntools: [readFile]\n---\nInspect the workspace.",
        "schema.json" to """{"type":"object","properties":{"text":{"type":"string"}},"additionalProperties":false}""",
        "mcp.json" to """{"mcpServers":{"remote":{"url":"https://example.com/mcp","headers":{"X-Fixture":"value"}},"local":{"command":"node","args":["server.js"],"env":{"FIXTURE":"yes"}}}}""",
        "pages/work.html" to "<html>workspace<script src=\"extra.js\"></script></html>",
        "pages/overlay.html" to "<html>overlay</html>",
        "pages/config.html" to "<html>configuration</html>",
        "pages/extra.js" to "globalThis.fixture = true",
        "extra/unreferenced.js" to "globalThis.extra = true"
    )
    fun archive(): ByteArray = PluginCoreFixtures.archive(manifest(), files().map { it.key to it.value.toByteArray() })
}
