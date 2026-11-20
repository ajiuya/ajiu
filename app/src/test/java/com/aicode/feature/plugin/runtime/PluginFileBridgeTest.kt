package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.LocalFolderUnavailableReason
import com.aicode.feature.workspace.domain.RemoteAgentConfigProfile
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.localRootPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private typealias FilePermissionCheck = suspend (String, JsonObject, PluginApprovalOperation, String) -> Unit

class PluginFileBridgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bridge = PluginFileBridge(FileBackendRouting())
    private val allow: FilePermissionCheck = { _, _, _, _ -> }
    private val noPrompt: FilePermissionCheck = { _, _, _, _ -> throw AssertionError("Unexpected approval") }
    private lateinit var data: File
    private lateinit var workspace: File
    private lateinit var granted: File
    private lateinit var frozen: WorkspaceExecutionContext
    private lateinit var context: PluginInvocationContext
    private lateinit var capture: PluginCapture
    private val virtual = localRootPath("device", "epoch-one")
    private var selection = 0L
    private var registry = 0L
    private var integrityCheck: () -> Unit = {}

    @Before fun prepare() {
        data = temporary.newFolder("files", "aicode", "plugins", "data", "fixture.files")
        workspace = temporary.newFolder("work")
        granted = temporary.newFolder("grant")
        val record = PluginRecord("fixture.files", "1.0.0", true, 1, archiveSha256 = "archive", manifestSha256 = "manifest",
            installRevision = "revision", contentHash = "hash", fileHashes = emptyMap())
        capture = PluginCapture(record, mockk(), PluginContent(), temporary.newFolder("installed"), { integrityCheck() }, {})
        frozen = local()
        context = invocation(frozen)
    }

    private fun snapshot(available: Boolean = true): LocalFolderSnapshot {
        val revision = registry
        val grant = LocalFolderGrant("device", "epoch-one", "Device", virtual, available,
            if (available) null else LocalFolderUnavailableReason.READ_WRITE_REQUIRED)
        return LocalFolderSnapshot(revision, listOf(grant), listOf(CapturedLocalFolderRoot(grant, granted))) {
            if (registry != revision) throw CancellationException("Grant registry changed")
        }
    }

    private fun local(root: File = workspace, grants: LocalFolderSnapshot = snapshot()): WorkspaceExecutionContext {
        val mapper = mockk<WorkspacePathMapper>()
        val mapping = WorkspacePathMapper.ExecutionMapping(ContainerProfile.BUILTIN_ALPINE, root,
            temporary.root.resolve("container-root").apply { mkdirs() }, data.parentFile.parentFile.parentFile, "/root")
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "local", LocalFileAccess(mapper), mapping, null, grants) {}
        every { mapper.resolveFile(any(), any()) } answers {
            assertSame(execution, WorkspaceExecutionContext.current())
            val path = firstArg<String>()
            val entry = when {
                path == "~/workspace" -> root
                path.startsWith("~/workspace/") -> File(root, path.removePrefix("~/workspace/"))
                path == "/root/.aicode" -> mapping.aicodeRoot
                path.startsWith("/root/.aicode/") -> File(mapping.aicodeRoot, path.removePrefix("/root/.aicode/"))
                path.startsWith('/') -> File(mapping.rootfsRoot, path.removePrefix("/"))
                else -> error("Unexpected file path: $path")
            }
            if (secondArg<Boolean>()) entry.canonicalFile else File(entry.parentFile.canonicalFile, entry.name)
        }
        return execution
    }

    private fun invocation(execution: WorkspaceExecutionContext?, root: String = execution?.remoteSource?.workspaceRoot
        ?: execution?.localMapping?.workspaceRoot?.path.orEmpty()): PluginInvocationContext {
        val revision = selection
        return PluginInvocationContext(capture, execution, root, "file-test", data, emptyFlow()) {
            if (selection != revision) throw CancellationException("Selected workspace changed")
        }
    }

    private suspend fun call(method: String, params: JsonObject, ctx: PluginInvocationContext = context,
        authorize: FilePermissionCheck = allow): JsonObject = bridge.call(ctx, method, params, authorize).jsonObject

    private fun path(value: String) = buildJsonObject { put("path", value) }
    private fun text(path: String, value: String) = buildJsonObject { put("path", path); put("content", value) }
    private fun pair(from: String, to: String) = buildJsonObject { put("from", from); put("to", to) }
    private fun binary(path: String, base64: String) = buildJsonObject { put("path", path); put("base64", base64) }
    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.names(): List<String> = getValue("entries").jsonArray.map { it.jsonObject.value("name") }
    private suspend fun failWith(code: String, block: suspend () -> Unit) {
        try { block() } catch (e: PluginHostException) { assertEquals(code, e.code); return }
        throw AssertionError("Expected $code")
    }
    private suspend fun cancelled(block: suspend () -> Unit) {
        try { block() } catch (_: CancellationException) { return }
        throw AssertionError("Expected cancellation")
    }

    @Test fun fileMethodsRoundTripRawBytesAndEmptyEntriesWithinPrivateData() = runBlocking<Unit> {
        val privateContext = invocation(null)
        val raw = byteArrayOf(0, -1, 42, -128, 0, 19)
        val encoded = Base64.getEncoder().encodeToString(raw)
        var writes = 0
        val approve: FilePermissionCheck = { tool, _, operation, _ ->
            assertEquals("writeFile", tool)
            assertTrue(operation == PluginApprovalOperation.WRITE_FILES || operation == PluginApprovalOperation.DELETE_FILES)
            writes++
        }
        call("fs.writeBinary", binary("data://nested/tree/sample", encoded), privateContext, approve)
        assertArrayEquals(raw, data.resolve("nested/tree/sample").readBytes())
        val read = call("fs.readBinary", path("data://nested/tree/sample"), privateContext, noPrompt)
        assertEquals(encoded, read.value("base64")); assertEquals("6", read.value("size"))
        call("fs.copy", pair("data://nested/tree/sample", "data://copies/raw"), privateContext, approve)
        call("fs.rename", pair("data://copies/raw", "data://copies/moved"), privateContext, approve)
        call("fs.delete", path("data://copies/moved"), privateContext, approve)
        assertFalse(data.resolve("copies/moved").exists())
        call("fs.mkdir", path("data://one/two"), privateContext, approve)
        call("fs.copy", pair("data://one/two", "data://empty"), privateContext, approve)
        call("fs.rename", pair("data://empty", "data://renamed"), privateContext, approve)
        assertEquals("true", call("fs.stat", path("data://renamed"), privateContext, noPrompt).value("isDir"))
        call("fs.delete", path("data://renamed"), privateContext, approve)
        call("fs.writeBinary", binary("data://zero", ""), privateContext, approve)
        assertArrayEquals(byteArrayOf(), data.resolve("zero").readBytes())
        call("fs.write", text("data://text", "汉"), privateContext, approve)
        call("fs.append", text("data://text", "字"), privateContext, approve)
        assertEquals("汉字", call("fs.read", path("data://text"), privateContext, noPrompt).value("content"))
        assertEquals("6", call("fs.stat", path("data://text"), privateContext, noPrompt).value("size"))
        assertFalse(call("fs.list", path("data://"), privateContext, noPrompt).names().any { it.startsWith(".aicode-transfer-") })
        assertEquals(11, writes)
    }

    @Test fun everyMutationWaitsForNativePermissionAndNeverTouchesEitherRootOnDenial() = runBlocking<Unit> {
        val requests = listOf<(String) -> Pair<String, JsonObject>>(
            { "fs.write" to text(it + "file", "new") }, { "fs.writeBinary" to binary(it + "file", "AA==") },
            { "fs.append" to text(it + "file", "new") }, { "fs.mkdir" to path(it + "new/path") },
            { "fs.delete" to path(it + "file") }, { "fs.copy" to pair(it + "file", it + "new/copy") },
            { "fs.rename" to pair(it + "file", it + "renamed") }
        )
        for ((prefix, root) in listOf("data://" to data, "~/workspace/" to workspace, "$virtual/" to granted)) {
            root.resolve("file").writeText("old")
            for ((index, factory) in requests.withIndex()) {
                val (method, args) = factory(prefix)
                val code = if (index % 2 == 0) "SYSTEM_DENIED" else "USER_REJECTED"
                var prompts = 0
                failWith(code) {
                    call(method, args) { tool, _, op, _ ->
                        assertSame(frozen, WorkspaceExecutionContext.current()); assertEquals("writeFile", tool)
                        assertEquals(if (method == "fs.delete") PluginApprovalOperation.DELETE_FILES else PluginApprovalOperation.WRITE_FILES, op)
                        assertEquals(listOf("file"), root.list()!!.toList()); prompts++
                        throw PluginHostException(code, "Rejected by native host")
                    }
                }
                assertEquals(1, prompts); assertEquals("old", root.resolve("file").readText())
                assertEquals(listOf("file"), root.list()!!.toList())
            }
        }
    }

    @Test fun missingTypeAndSizeAreNotReportedOnDeniedWorkspaceReads() = runBlocking<Unit> {
        workspace.resolve("file").writeText("original")
        workspace.resolve("folder").mkdir()
        workspace.resolve("large").writeBytes(ByteArray(1024 * 1024 + 1))
        val cases = listOf("file" to "", "missing" to "NOT_FOUND", "folder" to "NOT_A_FILE", "file/child" to "NOT_A_DIRECTORY", "large" to "TOO_LARGE")
        for ((name, afterApproval) in cases) {
            var calls = 0
            failWith("SYSTEM_DENIED") { call("fs.read", path(name)) { tool, _, op, _ ->
                calls++; assertEquals("readFile", tool); assertEquals(PluginApprovalOperation.READ_FILES, op)
                throw PluginHostException("SYSTEM_DENIED", "Read denied")
            } }
            assertEquals(1, calls)
            if (afterApproval.isNotEmpty()) failWith(afterApproval) { call("fs.read", path(name)) }
        }
        failWith("USER_REJECTED") { call("fs.stat", path("missing")) { _, _, _, _ -> throw PluginHostException("USER_REJECTED", "Declined") } }
        assertEquals("false", call("fs.stat", path("missing")).value("exists"))
        assertEquals("original", workspace.resolve("file").readText())
    }

    @Test fun approvalDescribesRealDestinationAndByteCountsButNeverTheContent() = runBlocking<Unit> {
        val payload = "private-text-字"
        workspace.resolve("file").writeText("old")
        var approvals = 0
        call("fs.write", text("file", payload)) { tool, args, op, details ->
            approvals++; assertEquals("writeFile", tool); assertEquals(PluginApprovalOperation.WRITE_FILES, op)
            assertEquals("~/workspace/file", args.value("path")); assertEquals("true", args.value("overwrite"))
            val shown = Json.parseToJsonElement(details).jsonObject
            assertEquals("fs.write", shown.value("method")); assertEquals(workspace.resolve("file").path, shown.value("path"))
            assertEquals("3", shown.value("replacesBytes")); assertEquals(payload.toByteArray().size.toString(), shown.value("bytes"))
            assertFalse(details.contains(payload)); assertFalse(args.toString().contains(payload))
        }
        call("fs.copy", pair("file", "dest/copy")) { _, args, _, details ->
            approvals++; assertEquals("~/workspace/file", args.value("from")); assertEquals("~/workspace/dest/copy", args.value("path"))
            val shown = Json.parseToJsonElement(details).jsonObject
            assertEquals(workspace.resolve("file").path, shown.value("from")); assertEquals(workspace.resolve("dest/copy").path, shown.value("to"))
            assertFalse(details.contains(payload)); assertFalse(workspace.resolve("dest").exists())
        }
        assertEquals(2, approvals); assertEquals(payload, workspace.resolve("dest/copy").readText())
    }

    @Test fun scopesAndProtectedRootsAreNeverTransferEndpoints() = runBlocking<Unit> {
        data.resolve("file").writeText("private")
        call("fs.copy", pair("data://file", "~/workspace/file"))
        assertEquals("private", workspace.resolve("file").readText())
        call("fs.rename", pair("~/workspace/file", "$virtual/file"))
        assertEquals("private", granted.resolve("file").readText()); assertFalse(workspace.resolve("file").exists())
        for (method in listOf("fs.copy", "fs.rename")) cancelled {
            call(method, pair("$virtual/file", "${localRootPath("other", "epoch")}/file"), authorize = noPrompt)
        }
        for (root in listOf("data://", "~/workspace", virtual)) {
            failWith("PATH_FORBIDDEN") { call("fs.delete", path(root), authorize = noPrompt) }
            failWith("PATH_FORBIDDEN") { call("fs.mkdir", path(root), authorize = noPrompt) }
            val file = if (root == "data://") root + "file" else "$root/file"
            failWith("PATH_FORBIDDEN") { call("fs.copy", pair(root, file), authorize = noPrompt) }
            failWith("PATH_FORBIDDEN") { call("fs.rename", pair(file, root), authorize = noPrompt) }
        }
        for (value in listOf(data.path, workspace.resolve("file").path)) assertEquals("false", call("fs.stat", path(value)).value("exists"))
        assertEquals("true", call("fs.stat", path("/")).value("isDir"))
        for (value in listOf("../other", "~/workspace/a/../b", "data://../outside", "data:///ambiguous")) {
            failWith("PATH_FORBIDDEN") { call("fs.stat", path(value), authorize = noPrompt) }
        }
        assertEquals("private", data.resolve("file").readText()); assertTrue(workspace.list()!!.isEmpty())
    }

    @Test fun dependenciesHostControlsAndLinksAreNeitherTraversedNorReturnedByListing() = runBlocking<Unit> {
        val deps = data.resolve("dependencies").apply { mkdir() }
        deps.resolve("prepared").writeText("private")
        val ordinary = data.resolve("ordinary").apply { writeText("contents") }
        Files.createSymbolicLink(data.resolve("alias").toPath(), ordinary.toPath())
        Files.createSymbolicLink(data.resolve("folder-link").toPath(), deps.toPath())
        for (part in listOf("dependencies", "Dependencies/prepared", "folder-link/prepared", "alias", ".aicode-transfer-owned/payload")) {
            failWith("PATH_FORBIDDEN") { call("fs.read", path("data://$part"), authorize = noPrompt) }
        }
        assertEquals(listOf("ordinary"), call("fs.list", path("data://"), authorize = noPrompt).names())
        val broadRoot = temporary.root.resolve("files")
        val broad = invocation(local(broadRoot))
        broadRoot.resolve("plugin-registry.json").writeText("registry")
        for (value in listOf("aicode/plugins/data/fixture.files/dependencies/prepared", "aicode/plugins/installed/other", "plugin-registry.json", "plugin-registry.json.install")) {
            failWith("PATH_FORBIDDEN") { call("fs.stat", path(value), broad, noPrompt) }
        }
        assertFalse(call("fs.list", path(""), broad).names().contains("plugin-registry.json"))
        assertEquals("private", deps.resolve("prepared").readText())
    }

    @Test fun nonemptyDirectoriesAreRejectedAsAWholeAndEmptyDirectoriesRemainSupported() = runBlocking<Unit> {
        val tree = granted.resolve("tree").apply { mkdir() }
        tree.resolve("first").writeText("keep")
        workspace.resolve("outside").writeText("untouched")
        Files.createSymbolicLink(tree.resolve("link").toPath(), workspace.resolve("outside").toPath())
        var count = 0
        call("fs.copy", pair("$virtual/tree", "$virtual/copy")) { _, _, _, _ -> count++ }
        assertEquals("keep", granted.resolve("copy/first").readText())
        assertEquals(workspace.resolve("outside").toPath(), Files.readSymbolicLink(granted.resolve("copy/link").toPath()))
        call("fs.rename", pair("$virtual/tree", "$virtual/new")) { _, _, _, _ -> count++ }
        assertFalse(tree.exists()); assertEquals("keep", granted.resolve("new/first").readText())
        call("fs.delete", path("$virtual/new")) { _, _, _, _ -> count++ }
        call("fs.delete", path("$virtual/copy")) { _, _, _, _ -> count++ }
        assertEquals(4, count); assertEquals("untouched", workspace.resolve("outside").readText())
        assertFalse(granted.resolve("copy").exists()); assertFalse(granted.resolve("new").exists())
        call("fs.mkdir", path("$virtual/new/empty")); call("fs.copy", pair("$virtual/new/empty", "$virtual/copy"))
        call("fs.rename", pair("$virtual/copy", "$virtual/renamed")); call("fs.delete", path("$virtual/renamed"))
        assertTrue(granted.resolve("new/empty").isDirectory); assertFalse(granted.resolve("renamed").exists())
    }

    @Test fun copyAndRenameBindSourceAndDestinationBeforeTheSingleApproval() = runBlocking<Unit> {
        for (method in listOf("fs.copy", "fs.rename")) {
            val folder = workspace.resolve(method).apply { mkdir() }
            val from = folder.resolve("from").apply { writeText("old") }; val to = folder.resolve("to")
            val args = pair("$method/from", "$method/to")
            var prompts = 0
            cancelled { call(method, args) { _, _, _, _ ->
                prompts++
                val replacement = folder.resolve("replacement").apply { writeText("new") }
                Files.move(replacement.toPath(), from.toPath(), REPLACE_EXISTING)
            } }
            assertFalse(to.exists()); assertEquals("new", from.readText())
            cancelled { call(method, args) { _, _, _, _ -> prompts++; to.writeText("the other writer") } }
            assertEquals(2, prompts); assertEquals("the other writer", to.readText()); assertEquals("new", from.readText())
            failWith("PATH_CONFLICT") { call(method, args) }
            call(method, JsonObject(args + ("overwrite" to JsonPrimitive(true))))
            assertEquals("new", to.readText())
            assertEquals(method != "fs.rename", from.exists())
        }
    }

    @Test fun mismatchedWorkspaceContextGrantEpochAndReadOnlyRootCannotWrite() = runBlocking<Unit> {
        val other = local()
        withContext(other) { cancelled { call("fs.write", text("file", "bad"), authorize = noPrompt) } }
        assertNull(WorkspaceExecutionContext.current())
        cancelled { call("fs.write", text("${localRootPath("device", "old")}/file", "bad"), authorize = noPrompt) }
        val readOnly = invocation(local(grants = snapshot(available = false)))
        failWith("FILE_READ_ONLY") { call("fs.write", text("$virtual/file", "bad"), readOnly, noPrompt) }
        registry++
        cancelled { call("fs.write", text("data://file", "bad"), authorize = noPrompt) }
        assertTrue(granted.list()!!.isEmpty()); assertTrue(workspace.list()!!.isEmpty()); assertTrue(data.list()!!.isEmpty())
    }

    @Test fun staleApprovalCannotBeAppliedEvenWhenTheSameWorkspaceIsSelectedAgain() = runBlocking<Unit> {
        var approvals = 0
        cancelled { call("fs.write", text("data://nested/file", "old")) { _, _, _, _ -> approvals++; selection += 2 } }
        cancelled { call("fs.write", text("data://nested/file", "old"), authorize = noPrompt) }
        assertTrue(data.list()!!.isEmpty())
        val fresh = invocation(frozen)
        call("fs.write", text("data://nested/file", "new"), fresh) { _, _, _, _ -> approvals++ }
        assertEquals(2, approvals); assertEquals("new", data.resolve("nested/file").readText())
    }

    @Test fun samePathDirectoryReplacementAndStalePluginHashAbortBeforePublication() = runBlocking<Unit> {
        cancelled { call("fs.write", text("data://a/b", "old")) { _, _, _, _ ->
            Files.move(data.toPath(), data.parentFile.resolve("previous").toPath()); assertTrue(data.mkdir())
        } }
        assertTrue(data.list()!!.isEmpty()); assertTrue(data.parentFile.resolve("previous").list()!!.isEmpty())
        val fresh = invocation(frozen)
        cancelled { call("fs.write", text("data://a/b", "old"), fresh) { _, _, _, _ ->
            integrityCheck = { throw CancellationException("Capture changed") }
        } }
        assertFalse(data.resolve("a").exists())
    }

    @Test fun revokedAfterStageWriteDoesNotReplaceTheTargetOrLeaveNewParents() = runBlocking<Unit> {
        data.resolve("existing").writeText("preserved")
        for (target in listOf("existing", "new/child")) {
            var staged = false
            cancelled { call("fs.write", text("data://$target", "changed")) { _, _, _, _ ->
                integrityCheck = {
                    if (data.list()!!.any { it.startsWith(".aicode-transfer-") }) {
                        staged = true
                        throw CancellationException("Authority revoked before publication")
                    }
                }
            } }
            integrityCheck = {}
            assertTrue(staged); assertEquals(listOf("existing"), data.list()!!.toList())
            assertEquals("preserved", data.resolve("existing").readText())
        }
    }

    @Test fun protectedAncestorAndIntermediateLinksDoNotBecomeWritable() = runBlocking<Unit> {
        val inside = workspace.resolve("parent/grant").apply { mkdirs() }
        val localRoot = LocalFolderGrant("device", "epoch-one", "Nested", virtual, true)
        val roots = LocalFolderSnapshot(1, listOf(localRoot), listOf(CapturedLocalFolderRoot(localRoot, inside))) {}
        val ctx = invocation(local(grants = roots))
        for (name in listOf("parent/grant", "parent")) {
            failWith("PATH_FORBIDDEN") { call("fs.delete", path(name), ctx, noPrompt) }
            failWith("PATH_FORBIDDEN") { call("fs.rename", pair(name, "moved"), ctx, noPrompt) }
        }
        Files.createSymbolicLink(workspace.resolve("alias").toPath(), granted.toPath())
        failWith("PATH_FORBIDDEN") { call("fs.write", text("alias/child", "denied"), ctx, noPrompt) }
        val first = workspace.resolve("plain").apply { mkdir() }
        failWith("PATH_FORBIDDEN") { call("fs.write", text("plain/file", "denied"), ctx) { _, _, _, _ ->
            Files.move(first.toPath(), workspace.resolve("previous").toPath()); Files.createSymbolicLink(first.toPath(), granted.toPath())
        } }
        assertTrue(inside.isDirectory); assertTrue(granted.list()!!.isEmpty()); assertFalse(workspace.resolve("moved").exists())
    }

    @Test fun byteAndListingLimitsDoNotTruncateOrPublishPartialContent() = runBlocking<Unit> {
        val limit = 1024 * 1024
        val value = ByteArray(limit) { (it % 253).toByte() }
        data.resolve("full").writeBytes(value)
        failWith("TOO_LARGE") { call("fs.append", text("data://full", "x")) }
        failWith("TOO_LARGE") { call("fs.write", text("data://full", "字".repeat(limit / 3 + 1)), authorize = noPrompt) }
        failWith("TOO_LARGE") { call("fs.writeBinary", binary("data://full", Base64.getEncoder().encodeToString(value + 0.toByte())), authorize = noPrompt) }
        assertArrayEquals(value, data.resolve("full").readBytes())
        assertEquals(limit.toString(), call("fs.readBinary", path("data://full"), authorize = noPrompt).value("size"))
        val directory = data.resolve("many").apply { mkdir() }
        repeat(513) { directory.resolve("entry_$it").createNewFile() }
        val listed = call("fs.list", path("data://many"), authorize = noPrompt)
        assertEquals(512, listed.names().distinct().size); assertEquals("true", listed.value("truncated"))
        failWith("PARAM_INVALID") { call("fs.writeBinary", binary("data://bad", "not base64"), authorize = noPrompt) }
        failWith("PARAM_INVALID") { call("fs.delete", buildJsonObject { put("path", "data://many"); put("recursive", true) }, authorize = noPrompt) }
        assertFalse(data.resolve("bad").exists())
    }

    @Test fun approvalIsRejectedRatherThanTruncatingLongSourceOrDestination() = runBlocking<Unit> {
        val prefix = (1..16).joinToString("/") { "a".repeat(245) }
        val path = "$prefix/${"z".repeat(120)}"
        failWith("APPROVAL_TOO_LARGE") { call("fs.copy", pair("data://$path-a", "data://$path-b"), authorize = noPrompt) }
        assertTrue(data.list()!!.isEmpty())
    }

    @Test fun recursiveStagingCancellationPreservesExistingTargetAndRemovesOnlyOwnedStage() = runBlocking<Unit> {
        val source = data.resolve("source/sub").apply { mkdirs() }
        source.resolve("file").writeText("new data")
        val destination = data.resolve("destination").apply { mkdir() }
        destination.resolve("existing").writeText("preserved")
        var staged = false
        cancelled { call("fs.copy", buildJsonObject { put("from", "data://source"); put("to", "data://destination"); put("overwrite", true) }) { _, _, _, _ ->
            integrityCheck = {
                if (data.list()!!.any { it.startsWith(".aicode-transfer-") }) {
                    staged = true
                    throw CancellationException("Authority revoked while a recursive stage is owned")
                }
            }
        } }
        integrityCheck = {}
        assertTrue(staged)
        assertEquals("preserved", destination.resolve("existing").readText())
        assertEquals("new data", source.resolve("file").readText())
        assertFalse(destination.resolve("sub").exists())
        assertFalse(data.list()!!.any { it.startsWith(".aicode-transfer-") })
        call("fs.copy", buildJsonObject { put("from", "data://source"); put("to", "data://destination"); put("overwrite", true) })
        assertFalse(destination.resolve("existing").exists())
        assertEquals("new data", destination.resolve("sub/file").readText())
        call("fs.delete", path("data://source"))
        assertFalse(data.resolve("source").exists()); assertEquals("new data", destination.resolve("sub/file").readText())
    }

    @Test fun deniedRecursiveCopyDoesNotReadSourceContentBeforeNativeDecision() = runBlocking<Unit> {
        val source = data.resolve("source").apply { mkdir() }
        val oversized = source.resolve("oversized")
        java.io.RandomAccessFile(oversized, "rw").use { it.setLength(128L * 1024 * 1024 + 1) }
        var approvals = 0
        failWith("SYSTEM_DENIED") { call("fs.copy", pair("data://source", "data://target")) { _, _, _, _ ->
            approvals++
            assertFalse(data.resolve("target").exists())
            throw PluginHostException("SYSTEM_DENIED", "Native policy rejected the recursive copy")
        } }
        assertEquals(1, approvals)
        assertEquals(128L * 1024 * 1024 + 1, oversized.length())
        assertFalse(data.resolve("target").exists())
        assertFalse(data.list()!!.any { it.startsWith(".aicode-transfer-") })
    }

    @Test fun rootfsAndHomeReadRangeNeverTreatsAndroidHostAbsolutePathAsRawHostAccess() = runBlocking<Unit> {
        val rootfs = requireNotNull(frozen.localMapping).rootfsRoot
        rootfs.resolve("etc").mkdir()
        rootfs.resolve("etc/fixture").writeText("container system")
        rootfs.resolve("root").mkdir()
        rootfs.resolve("root/fixture").writeText("container home")
        assertEquals("container system", call("fs.read", path("/etc/fixture")).value("content"))
        assertEquals("container home", call("fs.read", path("~/fixture")).value("content"))
        assertEquals("false", call("fs.stat", path(data.resolve("private").path)).value("exists"))
        failWith("PATH_FORBIDDEN") { call("fs.delete", path("/"), authorize = noPrompt) }
        assertEquals("container system", rootfs.resolve("etc/fixture").readText())
    }

    @Test fun remoteRelativeAliasAndAbsoluteFormsReadTheFrozenServerRootNotHome() = runBlocking<Unit> {
        val serverRoot = "/srv/test work/selected"
        val home = "/home/test-member"
        val source = RemoteSftpFileAccess.ExecutionSource(RemoteConnectionIdentity(mockk(), home), serverRoot, RemoteAgentConfigProfile("test", 1))
        val ssh = mockk<RemoteSshConnection>(relaxed = true)
        every { ssh.config } returns mockk { every { remoteWorkspacePath } returns serverRoot }
        val sftp = mockk<SFTPClient>(relaxed = true)
        val repository = mockk<WorkspaceRepository>()
        every { repository.currentPath() } returns serverRoot
        coEvery { ssh.openReadChannel(source.identity) } returns sftp
        val remote = RemoteSftpFileAccess(ssh, repository, mockk())
        val owner = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "ssh-test", remote, null, source, snapshot()) {}
        val ctx = invocation(owner)
        val contents = "from the selected folder".toByteArray()
        val visible = "$serverRoot/example"
        val expired = AtomicBoolean()
        var afterRead: () -> Unit = {}
        fun attribute(typeOfEntry: FileMode.Type, length: Long) = mockk<FileAttributes> {
            every { type } returns typeOfEntry
            every { size } returns length
            every { mtime } returns 1L
        }
        val directory = attribute(FileMode.Type.DIRECTORY, 0L)
        val file = attribute(FileMode.Type.REGULAR, contents.size.toLong())
        fun attributes(value: String): FileAttributes {
            assertSame(owner, WorkspaceExecutionContext.current())
            return when (value) {
                "/", "/srv", "/srv/test work", serverRoot, "/home", home, "$home/workspace" -> directory
                visible -> file
                else -> throw SFTPException(StatusCode.NO_SUCH_FILE, "Missing fixture entry")
            }
        }
        every { sftp.lstat(any()) } answers { attributes(firstArg()) }
        every { sftp.statExistence(any()) } answers { attributes(firstArg()) }
        every { sftp.canonicalize(any()) } answers { firstArg<String>() }
        every { sftp.open(visible) } answers {
            mockk<RemoteFile>(relaxed = true) {
                every { read(any(), any(), any(), any()) } answers {
                    val pos = firstArg<Long>().toInt(); val n = minOf(arg<Int>(3), contents.size - pos)
                    if (n <= 0) -1 else { contents.copyInto(secondArg(), thirdArg(), pos, pos + n); afterRead(); n }
                }
            }
        }
        call("fs.write", text("$virtual/on-device", "device"), ctx)
        assertEquals("device", granted.resolve("on-device").readText())
        assertEquals("device", call("fs.read", path("$virtual/on-device"), ctx).value("content"))
        failWith("USER_REJECTED") { call("fs.copy", pair("$virtual/on-device", visible), ctx) { _, _, _, _ ->
            throw PluginHostException("USER_REJECTED", "Cross-backend transfer declined")
        } }
        for (name in listOf("example", "~/workspace/example", visible)) {
            val result = call("fs.read", path(name), ctx) { _, _, _, details ->
                assertSame(owner, WorkspaceExecutionContext.current()); assertEquals(visible, Json.parseToJsonElement(details).jsonObject.value("path"))
            }
            assertEquals(contents.toString(Charsets.UTF_8), result.value("content"))
        }
        failWith("PATH_FORBIDDEN") { call("fs.read", path("$home/workspace/example"), ctx, noPrompt) }
        verify(exactly = 0) { sftp.open("$home/workspace/example") }
        verify(exactly = 3) { sftp.open(visible) }
        afterRead = { if (expired.compareAndSet(false, true)) selection++ }
        cancelled { call("fs.readBinary", path("example"), ctx) }
        assertTrue(expired.get())
    }
}
