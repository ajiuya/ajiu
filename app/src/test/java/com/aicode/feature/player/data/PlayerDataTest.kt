package com.aicode.feature.player.data

import com.aicode.feature.player.domain.PlayerItem
import com.aicode.feature.player.domain.PlayerRequest
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PlayerDataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun requestSerializesAllContractDefaults() {
        val json = Json { encodeDefaults = true }
        val objectValue = json.parseToJsonElement(json.encodeToString(PlayerRequest())).jsonObject
        assertEquals(setOf("mode", "src", "title", "items", "api", "query", "root", "referer", "videoSources", "videoId"), objectValue.keys)
        assertEquals("~/workspace", objectValue["root"]!!.jsonPrimitive.content)
        val item = PlayerItem("name", "https://cdn.example/v.mp4")
        assertEquals("video", json.parseToJsonElement(json.encodeToString(item)).jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun libraryEmitsSessionLocalUrlsAndHonorsBudgets() {
        fun entry(name: String, directory: Boolean) = FileEntry(name, directory, 500, 0)
        val tree = mapOf("~/workspace" to listOf(entry("a.mp4", false), entry("sub", true), entry("skip.txt", false), entry("../escape.mp4", false)),
            "~/workspace/sub" to listOf(entry("b.mp3", false), entry("deep", true)),
            "~/workspace/sub/deep" to listOf(entry("hidden.mp4", false)))
        val access = playerFileAccess(entries = { tree[it].orEmpty() }, isDirectory = tree::containsKey)
        fun library(maxDepth: Int = 5, maxItems: Int = 1000, maxEntries: Int = 5000) = PlayerLibrary(
            TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(),
            createCopy = { error("Listing must not copy files") }, maxDepth = maxDepth, maxItems = maxItems, maxEntries = maxEntries)
        library(maxDepth = 2, maxItems = 2).use {
            val items = it.json()["items"]!!.jsonArray
            assertEquals(2, items.size)
            assertEquals("audio", items[1].jsonObject["kind"]!!.jsonPrimitive.content)
            assertEquals("/T/local?path=~%2Fworkspace%2Fa.mp4", items[0].jsonObject["url"]!!.jsonPrimitive.content)
        }
        library(maxEntries = 1).use { assertEquals(1, it.json()["items"]!!.jsonArray.size) }
        library(maxDepth = 1).use { assertEquals(1, it.json()["items"]!!.jsonArray.size) }
    }

    @Test fun localAndLibraryRootCannotEscapeSessionRoot() {
        PlayerLibrary(TestPlayerFileExecution(playerFileAccess()), "~/workspace", "/T/", PlayerResources(),
            createCopy = { error("Path validation must not copy files") }).use { library ->
            assertEquals("~/workspace/a.mp4", library.checkedPath("~/workspace/x/../a.mp4"))
            for (path in listOf("~/workspace/../secret", "~/workspace-other/a", "/etc/passwd", "relative.mp4")) {
                assertThrows(PlayerHttpException::class.java) { library.checkedPath(path) }
            }
            assertThrows(PlayerHttpException::class.java) { library.json("~/workspace/../secret") }
        }
    }

    @Test fun remoteCopyIsReusedAndOwnedTempDeletedOnClose() {
        val source = temporary.newFile("provider-source").apply { writeText("0123456789") }
        var opens = 0
        val access = playerFileAccess(entries = { listOf(FileEntry("a.mp4", false, 10, 0)) },
            stream = { opens++; source.inputStream() }, copy = { error("Remote reads must own their stream") })
        val owned = File(temporary.root, "owned.copy")
        val library = PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(), createCopy = { owned })
        try {
            library.openValidated("~/workspace/a.mp4").use { assertEquals(10L, it.descriptor!!.length()) }
            library.openValidated("~/workspace/a.mp4").use { assertEquals(48, it.descriptor!!.read()) }
            assertEquals(1, opens)
            library.close()
            assertFalse(owned.exists())
            assertTrue("Provider source belongs to its backend", source.exists())
            assertThrows(IOException::class.java) { library.openValidated("~/workspace/a.mp4") }
        } finally { library.close() }
    }

    @Test fun activeDescriptorRetainsItsOwnedCopyUntilLastLeaseCloses() {
        val owned = File(temporary.root, "leased.copy")
        val access = playerFileAccess(entries = { listOf(FileEntry("a.mp4", false, 10, 0)) }, stream = { "0123456789".byteInputStream() })
        val library = PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(), createCopy = { owned })
        val lease = library.openValidated("~/workspace/a.mp4")
        try {
            library.close()
            assertTrue(owned.exists())
            assertEquals(48, lease.descriptor!!.read())
            lease.close()
            assertFalse(owned.exists())
            lease.close()
        } finally { lease.close(); library.close() }
    }

    @Test fun unsafeDirectoryEntriesAndSymlinksAreExcludedFromDiscovery() {
        val root = temporary.newFolder("library")
        val outside = temporary.newFile("outside.mp4").apply { writeText("outside") }
        val entries = listOf(FileEntry("../escape.mp4", false, 1, 0), FileEntry("nested/escape.mp4", false, 1, 0),
            FileEntry("bad\\name.mp4", false, 1, 0), FileEntry("bad\nname.mp4", false, 1, 0),
            FileEntry("link.mp4", false, outside.length(), 0, outside), FileEntry("good.mp3", false, 1, 0))
        val access = playerFileAccess(entries = { entries })
        PlayerLibrary(TestPlayerFileExecution(access), root.path, "/T/", PlayerResources(),
            createCopy = { error("Listing must not copy files") }).use { library ->
            val items = library.json()["items"]!!.jsonArray
            assertEquals(listOf("good.mp3"), items.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        }
    }
}
