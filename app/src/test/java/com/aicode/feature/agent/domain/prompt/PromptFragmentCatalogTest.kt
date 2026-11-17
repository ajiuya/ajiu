package com.aicode.feature.agent.domain.prompt

import android.content.Context
import android.content.res.AssetManager
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 四级来源片段的解析/保存/删除/重排：编号身份、优先级、落盘层。
 *
 * 注意：本地层（`prompts/`）只在**内置存在对应编号**时才会被解析（见 [PromptFragmentCatalog.resolve]），
 * 故这里让 assets 暴露一个内置文件名 `10-builtin.md`，本地同名文件才会生效。
 */
class PromptFragmentCatalogTest {

    private lateinit var root: File
    private lateinit var aicodeDir: File
    private lateinit var workspaceDir: File
    private lateinit var catalog: PromptFragmentCatalog
    private lateinit var catalogContext: Context
    private lateinit var installer: ContainerInstaller
    private lateinit var projectAicodeRoot: ProjectAicodeRoot

    private val globalDir get() = File(aicodeDir, "prompts.custom")
    private val localDir get() = File(aicodeDir, "prompts")
    private val projectDir get() = File(File(workspaceDir, ".aicode"), "prompts.custom")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("prompt-catalog-test").toFile()
        aicodeDir = File(root, "aicode").apply { mkdirs() }
        workspaceDir = File(root, "workspace").apply { mkdirs() }
        globalDir.mkdirs()
        localDir.mkdirs()

        catalogContext = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { catalogContext.assets } returns assets
        every { assets.list(any()) } returns arrayOf(BUILTIN_NAME)
        every { assets.open("prompts/$BUILTIN_NAME") } answers { "BUILTIN".byteInputStream() }
        installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns aicodeDir
        projectAicodeRoot = mockk<ProjectAicodeRoot>()
        every { projectAicodeRoot.forPath(any()) } answers { File(firstArg<String>(), ".aicode") }

        catalog = PromptFragmentCatalog(catalogContext, installer, projectAicodeRoot)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(dir: File, name: String, content: String): File {
        dir.mkdirs()
        return File(dir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    @Test
    fun list_同编号_取优先级最高的项目层() {
        write(globalDir, "10-g.md", "G")
        write(localDir, BUILTIN_NAME, "L")
        write(projectDir, "10-p.md", "P")

        val fragment = catalog.list(workspaceDir.path).first { it.number == 10 }

        assertEquals(PromptFragmentSource.PROJECT, fragment.source)
        assertEquals("P", fragment.content)
    }

    @Test
    fun list_无项目层_回退全局再回退本地() {
        write(localDir, BUILTIN_NAME, "L")
        write(globalDir, "10-g.md", "G")
        assertEquals(PromptFragmentSource.GLOBAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)

        globalDir.listFiles()!!.forEach { it.delete() }
        assertEquals(PromptFragmentSource.LOCAL, catalog.list(workspaceDir.path).first { it.number == 10 }.source)
    }

    @Test
    fun renderStatic_按编号升序拼接正文() {
        write(globalDir, "30-b.md", "BBB")
        write(globalDir, "10-a.md", "AAA")

        assertEquals("AAA\n\nBBB", catalog.renderStatic(workspaceDir.path))
    }

    @Test
    fun saveOverride_写到指定层并清掉旧编号() {
        catalog.saveOverride(12, "x", "X", workspaceDir.path, target = PromptFragmentSource.GLOBAL)
        assertTrue(File(globalDir, "12-x.md").isFile)

        catalog.saveOverride(
            20, "x", "X", workspaceDir.path,
            target = PromptFragmentSource.GLOBAL, previousNumber = 12
        )
        assertFalse("旧编号应被清掉", File(globalDir, "12-x.md").exists())
        assertTrue(File(globalDir, "20-x.md").isFile)
    }

    @Test
    fun deleteOverride_删除可写覆盖() {
        write(globalDir, "12-x.md", "X")
        assertTrue(catalog.deleteOverride(12, workspaceDir.path))
        assertFalse(File(globalDir, "12-x.md").exists())
    }

    @Test
    fun reorder_按新顺序重新编号并写入可写层() {
        write(globalDir, "10-a.md", "A")
        write(globalDir, "30-b.md", "B")

        val original = catalog.list(workspaceDir.path)
        val reversed = original.reversed()
        assertTrue(catalog.reorder(reversed, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals("B", after.first { it.number == 10 }.content)
        assertEquals("A", after.first { it.number == 30 }.content)
    }

    @Test
    fun list_默认启用并解析内置正文() {
        val fragment = catalog.list(workspaceDir.path).single()

        assertEquals(PromptFragmentSource.BUILTIN, fragment.source)
        assertTrue(fragment.enabled)
        assertEquals("BUILTIN", catalog.renderStatic(workspaceDir.path))
        assertEquals("", catalog.renderCustomOnly(workspaceDir.path))
    }

    @Test
    fun disabled_同编号多层保留最高层但不注入也不回退() {
        write(projectDir, "10-project.md", "PROJECT")
        write(globalDir, "10-global.md", "GLOBAL")
        write(localDir, BUILTIN_NAME, "LOCAL")
        write(globalDir, "20-other.md", "OTHER")

        assertTrue(catalog.setFragmentEnabled(10, false, workspaceDir.path))

        val fragment = catalog.list(workspaceDir.path).first { it.number == 10 }
        assertEquals(PromptFragmentSource.PROJECT, fragment.source)
        assertEquals("PROJECT", fragment.content)
        assertFalse(fragment.enabled)
        assertFalse(catalog.fragment(10, workspaceDir.path)!!.enabled)
        assertEquals("OTHER", catalog.renderStatic(workspaceDir.path))
        assertEquals("OTHER", catalog.renderCustomOnly(workspaceDir.path))
        assertEquals("PROJECT", File(projectDir, "10-project.md").readText())
        assertEquals("GLOBAL", File(globalDir, "10-global.md").readText())
        assertEquals("LOCAL", File(localDir, BUILTIN_NAME).readText())
    }

    @Test
    fun disabled_全局覆盖和本地及内置均可单独停用() {
        val global = write(globalDir, "10-global.md", "GLOBAL")
        val local = write(localDir, BUILTIN_NAME, "LOCAL")
        assertTrue(catalog.setFragmentEnabled(10, false, null))

        assertEquals(PromptFragmentSource.GLOBAL, catalog.fragment(10, null)!!.source)
        assertEquals("", catalog.renderStatic(null))
        assertEquals("", catalog.renderCustomOnly(null))
        assertTrue(global.delete())
        assertEquals(PromptFragmentSource.LOCAL, catalog.fragment(10, null)!!.source)
        assertFalse(catalog.fragment(10, null)!!.enabled)
        assertEquals("", catalog.renderStatic(null))
        assertTrue(local.delete())
        assertEquals(PromptFragmentSource.BUILTIN, catalog.fragment(10, null)!!.source)
        assertFalse(catalog.fragment(10, null)!!.enabled)
        assertEquals("", catalog.renderStatic(null))

        assertTrue(catalog.setFragmentEnabled(10, true, null))
        assertEquals("BUILTIN", catalog.renderStatic(null))
    }

    @Test
    fun setFragmentEnabled_项目显式启用覆盖全局禁用且隔离工作区() {
        val otherWorkspace = File(root, "other-workspace").apply { mkdirs() }
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertFalse(catalog.fragment(10, workspaceDir.path)!!.enabled)
        assertFalse(catalog.fragment(10, otherWorkspace.path)!!.enabled)

        assertTrue(catalog.setFragmentEnabled(10, true, workspaceDir.path))
        assertTrue(catalog.fragment(10, workspaceDir.path)!!.enabled)
        assertFalse(catalog.fragment(10, null)!!.enabled)
        assertFalse(catalog.fragment(10, otherWorkspace.path)!!.enabled)
        assertEquals("BUILTIN", catalog.renderStatic(workspaceDir.path))
        assertEquals("", catalog.renderStatic(otherWorkspace.path))
        assertEquals("""{"enabled":{"10":true}}""", File(projectDir, STATE_FILE).readText())
        assertEquals("""{"enabled":{"10":false}}""", File(globalDir, STATE_FILE).readText())
    }

    @Test
    fun setFragmentEnabled_重建目录实例后仍保留状态且不改写正文() {
        val file = write(globalDir, "10-global.md", "<!-- summary -->\nGLOBAL")
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        val metadata = File(globalDir, STATE_FILE).readText()

        val reloaded = PromptFragmentCatalog(catalogContext, installer, projectAicodeRoot)
        assertFalse(reloaded.list(null).single().enabled)
        assertEquals("", reloaded.renderStatic(null))
        assertEquals("<!-- summary -->\nGLOBAL", file.readText())
        assertEquals(metadata, File(globalDir, STATE_FILE).readText())
        assertTrue(globalDir.listFiles()!!.none { it.name.endsWith(".tmp") })

        assertTrue(reloaded.setFragmentEnabled(10, true, null))
        assertTrue(catalog.list(null).single().enabled)
        assertEquals("GLOBAL", catalog.renderStatic(null))
    }

    @Test
    fun setFragmentEnabled_并发写入保留全部编号状态且不留下临时文件() {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (0..19).map { number ->
                executor.submit<Boolean> {
                    start.await()
                    catalog.setFragmentEnabled(number, false, null)
                }
            }
            start.countDown()
            results.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }

            val expected = (0..19).joinToString(",", prefix = "{\"enabled\":{", postfix = "}}") { "\"$it\":false" }
            assertEquals(expected, File(globalDir, STATE_FILE).readText())
            assertFalse(catalog.fragment(10, null)!!.enabled)
            assertTrue(globalDir.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun setFragmentEnabled_远程项目通过映射目录写入且全局仍在App目录() {
        val remotePath = "/server/workspace"
        val mappedRoot = File(aicodeDir, "projects/server-workspace")
        every { projectAicodeRoot.forPath(remotePath) } returns mappedRoot

        assertTrue(catalog.setFragmentEnabled(10, false, remotePath))
        assertTrue(File(mappedRoot, "prompts.custom/$STATE_FILE").isFile)
        assertFalse(File(projectDir, STATE_FILE).exists())
        assertFalse(File(globalDir, STATE_FILE).exists())
        assertFalse(catalog.fragment(10, remotePath)!!.enabled)
        assertTrue(catalog.fragment(10, workspaceDir.path)!!.enabled)

        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertTrue(File(globalDir, STATE_FILE).isFile)
    }

    @Test
    fun saveOverride_编辑正文和改编号时状态随原片段移动() {
        write(projectDir, "12-old.md", "OLD")
        write(globalDir, "20-target.md", "TARGET")
        write(globalDir, "30-unrelated.md", "UNRELATED")
        assertTrue(catalog.setFragmentEnabled(12, false, workspaceDir.path))
        assertTrue(catalog.setFragmentEnabled(20, true, workspaceDir.path))
        assertTrue(catalog.setFragmentEnabled(30, false, workspaceDir.path))

        assertTrue(catalog.saveOverride(12, "edited", "EDITED", workspaceDir.path))
        assertFalse(catalog.fragment(12, workspaceDir.path)!!.enabled)
        assertEquals("EDITED", catalog.fragment(12, workspaceDir.path)!!.content)
        assertTrue(catalog.saveOverride(20, "moved", "MOVED", workspaceDir.path, previousNumber = 12))

        assertEquals(null, catalog.fragment(12, workspaceDir.path))
        assertFalse(catalog.fragment(20, workspaceDir.path)!!.enabled)
        assertEquals("MOVED", catalog.fragment(20, workspaceDir.path)!!.content)
        assertFalse(catalog.fragment(30, workspaceDir.path)!!.enabled)
        assertFalse(File(globalDir, "20-target.md").exists())
        assertFalse(File(projectDir, STATE_FILE).readText().contains("\"12\""))
        assertTrue(catalog.saveOverride(12, "new", "NEW", workspaceDir.path))
        assertTrue(catalog.fragment(12, workspaceDir.path)!!.enabled)
    }

    @Test
    fun saveOverride_改编号清掉两层旧状态且跟随原可写层() {
        write(globalDir, "12-original.md", "ORIGINAL")
        assertTrue(catalog.setFragmentEnabled(12, false, null))
        assertTrue(catalog.setFragmentEnabled(12, true, workspaceDir.path))
        assertTrue(catalog.setFragmentEnabled(20, false, workspaceDir.path))

        assertTrue(catalog.saveOverride(20, "moved", "MOVED", workspaceDir.path, previousNumber = 12))

        assertEquals(PromptFragmentSource.GLOBAL, catalog.fragment(20, workspaceDir.path)!!.source)
        assertTrue(catalog.fragment(20, workspaceDir.path)!!.enabled)
        assertFalse(catalog.fragment(20, null)!!.enabled)
        assertEquals("""{"enabled":{"20":false}}""", File(globalDir, STATE_FILE).readText())
        assertEquals("""{"enabled":{"20":true}}""", File(projectDir, STATE_FILE).readText())
        assertTrue(catalog.saveOverride(12, "new", "NEW", null))
        assertTrue(catalog.fragment(12, workspaceDir.path)!!.enabled)
    }

    @Test
    fun saveOverride_变更作用域时保留停用并清掉旧作用域状态() {
        write(globalDir, "12-original.md", "ORIGINAL")
        assertTrue(catalog.setFragmentEnabled(12, false, null))

        assertTrue(catalog.saveOverride(12, "project", "PROJECT", workspaceDir.path, target = PromptFragmentSource.PROJECT))
        assertFalse(catalog.fragment(12, workspaceDir.path)!!.enabled)
        assertFalse(File(globalDir, "12-original.md").exists())
        assertFalse(File(globalDir, STATE_FILE).exists())
        assertEquals("""{"enabled":{"12":false}}""", File(projectDir, STATE_FILE).readText())
    }

    @Test
    fun saveOverride_修改内置片段时保留停用状态() {
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertTrue(catalog.saveOverride(10, "custom", "CUSTOM", null))

        assertEquals(PromptFragmentSource.GLOBAL, catalog.fragment(10, null)!!.source)
        assertFalse(catalog.fragment(10, null)!!.enabled)
        assertEquals("", catalog.renderCustomOnly(null))
    }

    @Test
    fun reorder_停用状态随正文移动而不串编号() {
        write(globalDir, "10-a.md", "A")
        write(globalDir, "30-b.md", "B")
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertTrue(catalog.setFragmentEnabled(30, true, workspaceDir.path))
        assertTrue(catalog.setFragmentEnabled(80, false, workspaceDir.path))
        val reversed = catalog.list(workspaceDir.path).reversed()

        assertTrue(catalog.reorder(reversed, workspaceDir.path))

        val after = catalog.list(workspaceDir.path)
        assertEquals("B", after[0].content)
        assertTrue(after[0].enabled)
        assertEquals("A", after[1].content)
        assertFalse(after[1].enabled)
        assertEquals("B", catalog.renderStatic(workspaceDir.path))
        assertFalse(File(globalDir, STATE_FILE).exists())
        assertEquals("""{"enabled":{"10":true,"30":false,"80":false}}""", File(projectDir, STATE_FILE).readText())
        assertEquals("BUILTIN", catalog.renderStatic(null))
    }

    @Test
    fun reorder_列表和ViewModel预先重编号后状态仍随正文移动() {
        write(globalDir, "10-a.md", "A")
        write(globalDir, "30-b.md", "B")
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        val original = catalog.list(null)
        val numbers = original.map { it.number }.sorted()
        val renumbered = original.reversed().mapIndexed { index, fragment -> fragment.copy(number = numbers[index]) }

        assertTrue(catalog.reorder(renumbered, null))
        assertEquals("B", catalog.fragment(10, null)!!.content)
        assertTrue(catalog.fragment(10, null)!!.enabled)
        assertEquals("A", catalog.fragment(30, null)!!.content)
        assertFalse(catalog.fragment(30, null)!!.enabled)
        assertEquals("B", catalog.renderStatic(null))
    }

    @Test
    fun deleteOverride_只清理被删除层状态并回退下层() {
        write(globalDir, "10-global.md", "GLOBAL")
        write(projectDir, "10-project.md", "PROJECT")
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertTrue(catalog.setFragmentEnabled(10, true, workspaceDir.path))

        assertTrue(catalog.deleteOverride(10, workspaceDir.path))
        assertFalse(File(projectDir, STATE_FILE).exists())
        assertEquals(PromptFragmentSource.GLOBAL, catalog.fragment(10, workspaceDir.path)!!.source)
        assertFalse(catalog.fragment(10, workspaceDir.path)!!.enabled)
        assertTrue(File(globalDir, STATE_FILE).isFile)

        assertTrue(catalog.deleteOverride(10, workspaceDir.path))
        assertFalse(File(globalDir, STATE_FILE).exists())
        assertEquals(PromptFragmentSource.BUILTIN, catalog.fragment(10, workspaceDir.path)!!.source)
        assertTrue(catalog.fragment(10, workspaceDir.path)!!.enabled)
        assertEquals("BUILTIN", catalog.renderStatic(workspaceDir.path))
    }

    @Test
    fun deleteAllCustom_清理当前项目与全局状态但保留其他工作区和内部叶子() {
        val otherWorkspace = File(root, "other-workspace").apply { mkdirs() }
        val otherProjectDir = File(otherWorkspace, ".aicode/prompts.custom")
        write(globalDir, "20-global.md", "GLOBAL")
        write(projectDir, "30-project.md", "PROJECT")
        write(otherProjectDir, "40-other.md", "OTHER")
        val leaf = write(globalDir, "agent/title-generator.md", "TITLE")
        val note = write(projectDir, "notes.md", "NOTE")
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertTrue(catalog.setFragmentEnabled(30, false, workspaceDir.path))
        assertTrue(catalog.setFragmentEnabled(40, false, otherWorkspace.path))
        assertTrue(catalog.setBuiltinDisabled(true))

        assertEquals(2, catalog.deleteAllCustom(workspaceDir.path))

        assertFalse(File(globalDir, STATE_FILE).exists())
        assertFalse(File(projectDir, STATE_FILE).exists())
        assertTrue(File(otherProjectDir, STATE_FILE).isFile)
        assertEquals("OTHER", File(otherProjectDir, "40-other.md").readText())
        assertFalse(catalog.fragment(40, otherWorkspace.path)!!.enabled)
        assertTrue(catalog.fragment(10, workspaceDir.path)!!.enabled)
        assertTrue(catalog.isBuiltinDisabled())
        assertEquals("TITLE", leaf.readText())
        assertEquals("NOTE", note.readText())
        assertTrue(catalog.setBuiltinDisabled(false))
        assertEquals("BUILTIN", catalog.renderStatic(workspaceDir.path))
    }

    @Test
    fun deleteAllCustom_仅有内置开关状态时也能重置且无工作区不碰项目层() {
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertTrue(catalog.setFragmentEnabled(10, false, workspaceDir.path))

        assertEquals(0, catalog.deleteAllCustom(null))
        assertFalse(File(globalDir, STATE_FILE).exists())
        assertTrue(File(projectDir, STATE_FILE).isFile)
        assertTrue(catalog.fragment(10, null)!!.enabled)
        assertFalse(catalog.fragment(10, workspaceDir.path)!!.enabled)

        assertEquals(0, catalog.deleteAllCustom(workspaceDir.path))
        assertFalse(File(projectDir, STATE_FILE).exists())
        assertTrue(catalog.fragment(10, workspaceDir.path)!!.enabled)
    }

    @Test
    fun noBuiltin_与逐条开关共存且切回后仍保留状态() {
        write(localDir, BUILTIN_NAME, "LOCAL")
        write(globalDir, "20-custom.md", "CUSTOM")
        assertTrue(catalog.setFragmentEnabled(10, false, null))
        assertTrue(catalog.setFragmentEnabled(20, false, null))
        assertTrue(catalog.setBuiltinDisabled(true))

        val fragments = catalog.list(null)
        assertEquals(listOf(20), fragments.map { it.number })
        assertFalse(fragments.single().enabled)
        assertEquals("", catalog.renderCustomOnly(null))
        assertEquals("", catalog.renderStatic(null))

        assertTrue(catalog.setFragmentEnabled(20, true, null))
        assertTrue(catalog.isBuiltinDisabled())
        assertEquals("CUSTOM", catalog.renderStatic(null))
        assertTrue(catalog.setBuiltinDisabled(false))
        assertEquals(listOf(10, 20), catalog.list(null).map { it.number })
        assertFalse(catalog.fragment(10, null)!!.enabled)
        assertEquals("CUSTOM", catalog.renderStatic(null))
    }

    @Test
    fun metadata损坏_开关保存重排删除重置都不改写有效正文和原配置() {
        val file = write(globalDir, "10-original.md", "ORIGINAL")
        val projectFile = write(projectDir, "30-project.md", "PROJECT")
        val malformed = listOf(
            "{broken",
            """{"enabled":[]}""",
            """{"enabled":{"10":"false"}}""",
            """{"enabled":{"other":false}}"""
        )
        malformed.forEach { raw ->
            val metadata = write(globalDir, STATE_FILE, raw)
            val fragments = catalog.list(workspaceDir.path)
            assertNotNull(catalog.fragment(10, workspaceDir.path))

            assertFalse(catalog.setFragmentEnabled(10, false, null))
            assertFalse(catalog.saveOverride(20, "new", "NEW", workspaceDir.path, previousNumber = 10))
            assertFalse(catalog.reorder(fragments.reversed(), workspaceDir.path))
            assertFalse(catalog.deleteOverride(10, workspaceDir.path))
            assertEquals(0, catalog.deleteAllCustom(workspaceDir.path))

            assertEquals(raw, metadata.readText())
            assertEquals("ORIGINAL", file.readText())
            assertEquals("PROJECT", projectFile.readText())
            assertFalse(File(projectDir, "20-new.md").exists())
            assertFalse(File(projectDir, STATE_FILE).exists())
        }
    }

    private companion object {
        const val STATE_FILE = "prompts.json"
        /** 让本地层可解析的内置文件名（编号 10）。 */
        const val BUILTIN_NAME = "10-builtin.md"
    }
}
