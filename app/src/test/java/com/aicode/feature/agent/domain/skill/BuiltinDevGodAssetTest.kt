package com.aicode.feature.agent.domain.skill

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class BuiltinDevGodAssetTest {

    private val skillDir: File
        get() = listOf(
            File("src/main/assets/skills/dev-god"),
            File("app/src/main/assets/skills/dev-god")
        ).firstOrNull { it.isDirectory } ?: error("Missing dev-god assets in ${File("").absolutePath}")

    @Test
    fun entry_parsesWithAppParser_andKeepsUpstreamIdentity() {
        val text = File(skillDir, "SKILL.md").readText()
        val skill = SkillParser.parseText(text, "fallback")
        val header = Yaml().load<Map<String, Any>>(text.substringAfter("---\n").substringBefore("\n---"))
        val metadata = header.getValue("metadata") as Map<*, *>

        assertEquals("dev-god", skill.name)
        assertTrue(skill.description.startsWith("编程大神："))
        assertTrue(skill.description.length <= 500)
        assertTrue(skill.requiredTools.isEmpty())
        assertTrue(skill.instructions.startsWith("# 编程大神"))
        assertFalse(skill.instructions.contains("metadata:"))
        assertEquals("dev-god", metadata["slug"])
        assertEquals("编程大神", metadata["displayName"])
        assertEquals("1.2.4", metadata["version"])
        assertEquals("阿九工作台内置适配", metadata["adaptation"])
        assertTrue(skill.instructions.contains("https://skillhub.cn/skills/ake/dev-god"))
        assertTrue(skill.instructions.contains("未附独立许可证"))
    }

    @Test
    fun provenance_keepsOfficialPackageMetadata_andCompleteCompanionTree() {
        val root = skillDir
        val metadata = Json.parseToJsonElement(File(root, "_meta.json").readText()).jsonObject
        val source = metadata.getValue("source").jsonObject
        val adaptation = metadata.getValue("adaptation").jsonObject
        val files = root.walkTopDown().filter { it.isFile }.toList()

        assertEquals("783809", metadata.getValue("ownerId").jsonPrimitive.content)
        assertEquals("dev-god", metadata.getValue("slug").jsonPrimitive.content)
        assertEquals("1.2.4", metadata.getValue("version").jsonPrimitive.content)
        assertEquals("1791925341880", metadata.getValue("publishedAt").jsonPrimitive.content)
        assertEquals("阿客实验室", source.getValue("author").jsonPrimitive.content)
        assertEquals("@ake/dev-god", source.getValue("identifier").jsonPrimitive.content)
        assertEquals(
            "760a86b6452a656ff88af56740f130b4029dfd561d9fcfffcfdf48d762bc1a98",
            source.getValue("archiveSha256").jsonPrimitive.content
        )
        assertEquals(172922, source.getValue("archiveBytes").jsonPrimitive.int)
        assertEquals(386000, source.getValue("uncompressedBytes").jsonPrimitive.int)
        assertEquals(73, source.getValue("fileCount").jsonPrimitive.int)
        assertEquals(73, files.size)
        assertEquals(69, files.count { it.relativeTo(root).invariantSeparatorsPath.startsWith("references/") })
        assertEquals("not_declared_in_package", source.getValue("licenseStatus").jsonPrimitive.content)
        assertEquals("阿九工作台", adaptation.getValue("product").jsonPrimitive.content)
        assertEquals(1, adaptation.getValue("revision").jsonPrimitive.int)
        assertEquals(
            setOf(
                "SKILL.md", "_meta.json", "agents/openai.yaml", "scripts/project_probe.py",
                "references/continuity.md", "references/dependency-downloads.md",
                "references/wechat-devtools/index.md"
            ),
            adaptation.getValue("modifiedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet()
        )
        source.getValue("thirdPartyNotices").jsonArray.forEach {
            assertTrue(File(root, it.jsonPrimitive.content).isFile)
        }
        root.walkTopDown().forEach { assertFalse(Files.isSymbolicLink(it.toPath())) }
        assertFalse(files.any { it.extension in setOf("pyc", "jks", "p12", "pfx", "key", "pem") })
        assertEquals(listOf("project_probe.py"), File(root, "scripts").listFiles()!!.map { it.name })
    }

    @Test
    fun everyLocalMarkdownLink_resolvesInsidePackagedSkill() {
        val root = skillDir.canonicalFile
        val link = Regex("""!?\[[^\]]*]\(([^)\s]+)\)""")
        var localLinks = 0
        root.walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { markdown ->
            link.findAll(markdown.readText()).forEach { match ->
                val href = match.groupValues[1]
                if (!href.startsWith("https://") && !href.startsWith("http://") && !href.startsWith("#")) {
                    assertFalse("Absolute local link in $markdown: $href", href.startsWith("/") || '\\' in href)
                    val relative = href.substringBefore('#').substringBefore('?')
                    val target = File(markdown.parentFile, relative).canonicalFile
                    assertTrue("Link escapes skill: $markdown -> $href", target.toPath().startsWith(root.toPath()))
                    assertTrue("Missing companion: $markdown -> $href", target.isFile)
                    localLinks++
                }
            }
        }
        assertTrue("Companion references are unexpectedly missing", localLinks >= 218)
        assertTrue(File(root, "scripts/project_probe.py").isFile)
    }

    @Test
    fun hostToolReferences_matchCurrentNames_andPreserveApprovalBoundaries() {
        val root = skillDir
        val entry = File(root, "SKILL.md").readText()
        val instructions = entry + File(root, "references/continuity.md").readText()
        val sourceRoot = File(root, "../../../java/com/aicode/feature/agent/domain/tool").canonicalFile
        val declaration = Regex("""(?:override val name|const val NAME)\s*=\s*"([^"]+)"""")
        val registeredNames = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { declaration.findAll(it.readText()).map { match -> match.groupValues[1] } }.toSet()
        val hostTools = listOf(
            "readFile", "list", "search", "editFile", "writeFile", "Bash", "terminal",
            "browser", "viewImage", "sendFile", "loadSkill", "submitPlan", "Shizuku",
            "Root", "task", "messageParent", "todo"
        )

        hostTools.forEach { name ->
            assertTrue("Unknown host tool: $name", name in registeredNames)
            assertTrue("Missing host tool guidance: $name", instructions.contains("`$name`") || instructions.contains("$name("))
        }
        listOf(
            "submitPlan` 单独提交", "不得与任何其他调用并行或混在同一批", "DENY",
            "每次单独审批", "不自动 `git commit`", "用户明确暂不构建",
            "不把这项临时限制记成以后都不验证", "子代理不能嵌套派发"
        ).forEach { assertTrue("Missing boundary: $it", entry.contains(it)) }
        assertFalse(entry.contains("planMode"))
        assertFalse(entry.contains("EnterPlanMode"))
        assertFalse(entry.contains("ExitPlanMode"))
        assertTrue(entry.contains("<技能目录>/scripts/project_probe.py"))
        val downloads = File(root, "references/dependency-downloads.md").readText()
        assertTrue(downloads.contains("取得用户对换源或依赖安装的明确批准后再操作"))
        assertFalse(downloads.contains("无需为单次可逆切换反复确认"))
    }

    @Test
    fun wechatRegistry_remainsAnUnmodifiedProtocolSnapshot_withConsistentNames() {
        val registry = File(skillDir, "references/wechat-devtools/tools.yaml")
        val digest = MessageDigest.getInstance("SHA-256").digest(registry.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("5b1c4e6e2ccee842021f65d1b52de03da757b7d0bbe696740c72a5b44691f202", digest)
        val tools = Json.parseToJsonElement(registry.readText()).jsonObject.getValue("tools").jsonArray
        val ids = tools.map { it.jsonObject.getValue("id").jsonPrimitive.content }

        assertEquals(49, tools.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(2, tools.count { it.jsonObject["deprecated"]?.jsonPrimitive?.content == "true" })
        tools.forEach { value ->
            val tool = value.jsonObject
            val id = tool.getValue("id").jsonPrimitive.content
            assertTrue(id.matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue(tool.getValue("name").jsonPrimitive.content.isNotBlank())
            assertEquals("wechatide", tool.getValue("source").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(id, tool.getValue("execution").jsonObject.getValue("toolName").jsonPrimitive.content)
            tool.getValue("inputSchema").jsonObject.forEach { (_, parameter) ->
                assertTrue(parameter.jsonObject.getValue("type").jsonPrimitive.content in setOf("string", "number", "boolean", "object", "array"))
            }
        }
        val index = File(skillDir, "references/wechat-devtools/index.md").readText()
        assertTrue(index.contains("不会因加载本技能而注册这些工具"))
        assertTrue(index.contains("不是 App 的 `terminal` 标签"))
        assertFalse(index.contains("每次间隔约 10 秒"))
    }

    @Test
    fun sourceUrls_doNotIncludeCredentials_andPromptUsesAppSkillInvocation() {
        val metadata = Json.parseToJsonElement(File(skillDir, "_meta.json").readText()).jsonObject
        val source = metadata.getValue("source").jsonObject
        mapOf(
            "page" to "https://skillhub.cn/skills/ake/dev-god",
            "installationGuide" to "https://skillhub.cn/install/skillhub.md",
            "download" to "https://api.skillhub.cn/api/v1/download?slug=@ake/dev-god"
        ).forEach { (key, expected) ->
            val url = source.getValue(key).jsonPrimitive.content
            assertEquals(expected, url)
            assertEquals("https", URI(url).scheme)
            assertNull(URI(url).userInfo)
        }
        val prompt = Yaml().load<Map<String, Any>>(File(skillDir, "agents/openai.yaml").readText())
            .getValue("interface") as Map<*, *>
        assertEquals("编程大神", prompt["display_name"])
        assertTrue(prompt["default_prompt"].toString().contains("/dev-god"))
        assertTrue(prompt["default_prompt"].toString().contains("当前授权与审批边界"))
    }
}
