package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class RemoteGitCredentialEnvTest {
    @get:Rule val temp = TemporaryFolder()
    private val connection = mockk<RemoteSshConnection>()
    private val session = mockk<Session.Command>(relaxed = true)
    private val engine = RemoteSshEngine(connection)
    private val captured = slot<String>()
    private lateinit var home: File
    private lateinit var xdg: File

    @Before
    fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } just Runs
        every { FileLogger.v(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { connection.isConnected() } returns true
        every { connection.gitCredentialWorkspaceRoot } returns null
        every { connection.startExecSession(capture(captured)) } returns session
        every { session.inputStream } answers { ByteArrayInputStream(byteArrayOf()) }
        every { session.errorStream } answers { ByteArrayInputStream(byteArrayOf()) }
        every { session.outputStream } answers { ByteArrayOutputStream() }
        every { session.exitStatus } returns 0
        home = temp.newFolder("home with space")
        xdg = temp.newFolder("xdg")
        File(home, ".aicode").mkdirs()
        File(home, ".aicode/gitconfig.credential").writeText("[credential]\n    helper = fixture-helper\n")
        File(home, ".aicode/gitconfig").writeText("legacy user-owned content\n")
    }

    @After
    fun release() { unmockkObject(FileLogger) }

    private fun command(root: File?, body: String, streaming: Boolean = false): String = runBlocking {
        every { connection.gitCredentialWorkspaceRoot } returns root?.canonicalPath
        if (streaming) engine.runCommandStream(body, null, 1_000).toList()
        else engine.runCommandSync(body, null, 1_000)
        captured.captured
    }

    private fun shell(script: String, cwd: File, expectedExit: Int = 0): String {
        val builder = ProcessBuilder("sh", "-c", script).directory(cwd).redirectErrorStream(true)
        builder.environment().apply {
            keys.filter { it.startsWith("GIT_") }.forEach(::remove)
            put("HOME", home.canonicalPath)
            put("XDG_CONFIG_HOME", xdg.canonicalPath)
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_TERMINAL_PROMPT", "0")
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(output, expectedExit, process.waitFor())
        return output
    }

    private fun repo(parent: File, name: String): File = File(parent, name).apply {
        mkdirs()
        shell("git init -q", this)
    }

    @Test
    fun noInjectionLeavesBothSyncAndStreamingCommandTextUntouched() {
        assertEquals("git status", command(null, "git status"))
        assertEquals("git status", command(null, "git status", streaming = true))
    }

    @Test
    fun workspaceIncludeKeepsHomeAndXdgConfigVisibleAndGlobalWritesSurviveRefresh() {
        File(home, ".gitconfig").writeText("[user]\n    name = server identity\n")
        File(xdg, "git").mkdirs()
        File(xdg, "git/config").writeText("[http]\n    proxy = http://fixture.invalid\n")
        val root = temp.newFolder("workspace")
        val repo = repo(root, "project")
        assertEquals("server identity", shell(command(root, "git config user.name"), repo))
        assertEquals("http://fixture.invalid", shell(command(root, "git config http.proxy"), repo))
        assertEquals("fixture-helper", shell(command(root, "git config credential.helper"), repo))
        shell(command(root, "git config --global user.name 'saved identity'"), repo)
        File(home, ".aicode/gitconfig.credential").writeText("[credential]\n    helper = refreshed-helper\n")
        assertEquals("saved identity", shell(command(root, "git config user.name"), repo))
        assertEquals("saved identity", shell("git config --global user.name", repo))
        assertEquals("refreshed-helper", shell(command(root, "git config credential.helper"), repo))
        assertEquals("legacy user-owned content\n", File(home, ".aicode/gitconfig").readText())
        assertFalse(captured.captured.contains("GIT_CONFIG_GLOBAL"))
    }

    @Test
    fun conditionalIncludeDoesNotApplyToSiblingRepositoriesOrManualGit() {
        val root = temp.newFolder("workspace")
        val selected = repo(root, "project")
        val sibling = repo(temp.root, "workspace-other")
        val script = command(root, "git config --get credential.helper")
        assertEquals("fixture-helper", shell(script, selected))
        assertEquals("", shell(script, sibling, expectedExit = 1))
        assertEquals("", shell("git config --get credential.helper", selected, expectedExit = 1))
    }

    @Test
    fun quotedWorkspaceNamesAreLiteralGitPatternsAndCannotInjectShellCommands() {
        val root = temp.newFolder("workspace [x]*? '\$ literal")
        val selected = repo(root, "project")
        val sibling = repo(temp.root, "workspace xZZ '\$ literal")
        val script = command(root, "git config --get credential.helper", streaming = true)
        assertEquals("fixture-helper", shell(script, selected))
        assertEquals("", shell(script, sibling, expectedExit = 1))
        assertFalse(File(home, "literal").exists())
    }

    @Test
    fun credentialIncludeIsHarmlessAfterTheFileIsRevoked() {
        val root = temp.newFolder("workspace")
        val selected = repo(root, "project")
        val script = command(root, "git config --get credential.helper")
        assertTrue(File(home, ".aicode/gitconfig.credential").delete())
        assertEquals("", shell(script, selected, expectedExit = 1))
    }

    @Test
    fun explicitGlobalConfigIsNotReplacedByCredentialInjection() {
        val root = temp.newFolder("workspace")
        val selected = repo(root, "project")
        val explicit = File(temp.root, "explicit config").apply { writeText("[user]\n    name = explicit identity\n") }
        val script = "export GIT_CONFIG_GLOBAL=${shellQuote(explicit.canonicalPath)}; " +
            command(root, "git config user.name")
        assertEquals("explicit identity", shell(script, selected))
    }
}
