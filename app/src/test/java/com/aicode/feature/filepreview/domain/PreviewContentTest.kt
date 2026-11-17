package com.aicode.feature.filepreview.domain

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewContentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsEmptyAndOrdinaryUtf8WithoutClosingTheStream() {
        val empty = CountingStream(byteArrayOf())
        assertEquals(PreviewText("", false), readPreviewText(empty))
        assertFalse(empty.closed)
        val input = CountingStream("Hello, 中文 🙂\n".toByteArray())
        assertEquals(PreviewText("Hello, 中文 🙂\n", false), readPreviewText(input))
        assertFalse(input.closed)
    }

    @Test fun exactByteLimitIsNotTruncated() {
        val input = CountingStream("abcd".toByteArray())
        assertEquals(PreviewText("abcd", false), readPreviewText(input, 4))
        assertEquals(4, input.bytesRead)
        assertEquals(2, input.calls)
        assertFalse(input.closed)
    }

    @Test fun readsOnlyOneExtraByteToDetectTruncation() {
        val input = CountingStream("abcdefghij".toByteArray())
        assertEquals(PreviewText("abcd", true), readPreviewText(input, 4))
        assertEquals(5, input.bytesRead)
        assertEquals(1, input.calls)
        assertEquals(5, input.maximumRequest)
        assertEquals('f'.code, input.read())
        assertFalse(input.closed)
    }

    @Test fun defaultLimitIsTwoMebibytesAndBoundsTheReadRequest() {
        val input = CountingStream(ByteArray(PreviewLimits.TEXT_BYTES + 100) { 'x'.code.toByte() })
        val result = readPreviewText(input)
        assertEquals(2 * 1024 * 1024, result.text.length)
        assertTrue(result.truncated)
        assertEquals(PreviewLimits.TEXT_BYTES + 1, input.bytesRead)
        assertEquals(PreviewLimits.TEXT_BYTES + 1, input.maximumRequest)
        assertEquals(1, input.calls)
    }

    @Test fun zeroLimitStillUsesAtMostOneLookaheadByte() {
        val input = CountingStream("abc".toByteArray())
        assertEquals(PreviewText("", true), readPreviewText(input, 0))
        assertEquals(1, input.bytesRead)
        assertEquals(1, input.calls)
        val empty = CountingStream(byteArrayOf())
        assertEquals(PreviewText("", false), readPreviewText(empty, 0))
        assertEquals(1, empty.calls)
    }

    @Test fun invalidLimitsAreRejectedBeforeAnyRead() {
        listOf(-1, PreviewLimits.TEXT_BYTES + 1, Int.MAX_VALUE).forEach { limit ->
            val input = CountingStream("data".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { readPreviewText(input, limit) }
            assertEquals(0, input.calls)
            assertFalse(input.closed)
        }
    }

    @Test fun shortReadsCannotExceedTheByteOrReadCallBudget() {
        val input = CountingStream("x".repeat(100).toByteArray(), chunk = 1)
        assertEquals(PreviewText("x".repeat(16), true), readPreviewText(input, 16))
        assertEquals(17, input.bytesRead)
        assertEquals(17, input.calls)
        assertEquals(17, input.maximumRequest)
    }

    @Test fun zeroLengthBulkReadsMakeBoundedProgress() {
        val input = CountingStream("abcdef".toByteArray(), zeroBulkReads = true)
        assertEquals(PreviewText("abcd", true), readPreviewText(input, 4))
        assertEquals(5, input.bytesRead)
        assertEquals(10, input.calls)
        assertFalse(input.closed)
    }

    @Test fun aNeverEndingStreamIsReadOnlyToTheLimitPlusOne() {
        var bytes = 0
        var calls = 0
        val input = object : InputStream() {
            override fun read(): Int {
                bytes++
                calls++
                return 'x'.code
            }
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                target.fill('x'.code.toByte(), offset, offset + length)
                bytes += length
                calls++
                return length
            }
        }
        assertEquals(PreviewText("x".repeat(32), true), readPreviewText(input, 32))
        assertEquals(33, bytes)
        assertEquals(1, calls)
    }

    @Test fun ioFailuresPropagateWithoutClosingTheCallerStream() {
        val failure = IOException("read failed")
        var closed = false
        var calls = 0
        val input = object : InputStream() {
            override fun read(): Int = throw failure
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                calls++
                if (calls > 1) throw failure
                target[offset] = 'a'.code.toByte()
                return 1
            }
            override fun close() { closed = true }
        }
        assertSame(failure, assertThrows(IOException::class.java) { readPreviewText(input, 4) })
        assertEquals(2, calls)
        assertFalse(closed)
    }

    @Test fun cancellationIsNotConvertedIntoPreviewContent() {
        val failure = CancellationException("cancelled")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
        }
        assertSame(failure, assertThrows(CancellationException::class.java) { readPreviewText(input) })
    }

    @Test fun utf8TruncationDropsOnlyAnIncompleteTrailingCharacter() {
        val content = "A中🙂Z".toByteArray()
        mapOf(1 to "A", 2 to "A", 3 to "A", 4 to "A中", 5 to "A中", 6 to "A中", 7 to "A中", 8 to "A中🙂").forEach { (limit, expected) ->
            val result = readPreviewText(ByteArrayInputStream(content), limit)
            assertEquals("limit=$limit", PreviewText(expected, true), result)
            assertFalse(result.text.contains('\uFFFD'))
        }
        assertEquals(PreviewText("A中🙂Z", false), readPreviewText(ByteArrayInputStream(content), content.size))
    }

    @Test fun malformedUtf8IsNotSilentlyReplacedEvenInATruncatedPrefix() {
        listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF0.toByte(), 0x9F.toByte())
        ).forEach { bytes -> assertThrows(IOException::class.java) { readPreviewText(ByteArrayInputStream(bytes)) } }
        assertThrows(IOException::class.java) {
            readPreviewText(ByteArrayInputStream(byteArrayOf(0x61, 0xC3.toByte(), 0x28, 0x62)), 3)
        }
    }

    @Test fun utf8BomIsRemovedAndIncludedInTheByteBudget() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "中".toByteArray()
        assertEquals(PreviewText("中", false), readPreviewText(ByteArrayInputStream(bytes)))
        assertEquals(PreviewText("", true), readPreviewText(ByteArrayInputStream(bytes), 3))
        assertEquals(PreviewText("", true), readPreviewText(ByteArrayInputStream(bytes), 5))
        assertEquals(PreviewText("", true), readPreviewText(ByteArrayInputStream(bytes), 2))
    }

    @Test fun utf16LittleAndBigEndianRequireAndHonorTheirBoms() {
        listOf(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE,
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE
        ).forEach { (bom, charset) ->
            val bytes = bom + "A🙂中Z".toByteArray(charset)
            assertEquals(PreviewText("A🙂中Z", false), readPreviewText(ByteArrayInputStream(bytes)))
            mapOf(3 to "", 4 to "A", 5 to "A", 6 to "A", 7 to "A", 8 to "A🙂", 9 to "A🙂", 10 to "A🙂中").forEach { (limit, expected) ->
                assertEquals("$charset limit=$limit", PreviewText(expected, true), readPreviewText(ByteArrayInputStream(bytes), limit))
            }
        }
    }

    @Test fun bomOnlyTextIsEmptyAndDoesNotLeakABomCharacter() {
        listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        ).forEach { assertEquals(PreviewText("", false), readPreviewText(ByteArrayInputStream(it))) }
    }

    @Test fun malformedUtf16IsRejectedInsteadOfReplacingSurrogatesOrOddBytes() {
        listOf(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xD8.toByte()),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xDC.toByte(), 0x00)
        ).forEach { assertThrows(IOException::class.java) { readPreviewText(ByteArrayInputStream(it)) } }
    }

    @Test fun nulBytesAndDecodedNulsAreRejectedAsBinary() {
        listOf(
            byteArrayOf(0x00, 0x01, 0x02),
            "a\u0000b".toByteArray(),
            "AB".toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\u0000b".toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "a\u0000b".toByteArray(Charsets.UTF_16BE)
        ).forEach {
            val input = CountingStream(it)
            assertThrows(IOException::class.java) { readPreviewText(input) }
            assertFalse(input.closed)
        }
    }

    @Test fun truncatedReadsDoNotInspectOrDecodeBeyondTheBudget() {
        val input = CountingStream(byteArrayOf(0x61, 0x62, 0x63, 0x00, 0xFF.toByte()))
        assertEquals(PreviewText("abc", true), readPreviewText(input, 3))
        assertEquals(4, input.bytesRead)
        assertEquals(0xFF, input.read())
    }

    @Test fun validJsonIsPrettyPrintedWithItsStructureIntact() {
        val source = PreviewText("{\"a\":1,\"b\":[true,null]}", false)
        val expected = "{\n    \"a\": 1,\n    \"b\": [\n        true,\n        null\n    ]\n}"
        assertEquals(PreviewText(expected, false), prettyPreviewJson(source))
        assertEquals(Json.parseToJsonElement(source.text), Json.parseToJsonElement(prettyPreviewJson(source).text))
    }

    @Test fun jsonPrimitivesArraysAndUnicodeRoundTrip() {
        listOf("null", "true", "123.5", "\"中🙂\"", "[]", "{}", "[1,2,3]",
            "{\"escaped\":\"\\u4e2d\\ud83d\\ude42\",\"quote\":\"a\\\"b\"}").forEach {
            val result = prettyPreviewJson(PreviewText(it, false))
            assertFalse(result.truncated)
            assertEquals(it, Json.parseToJsonElement(it), Json.parseToJsonElement(result.text))
        }
    }

    @Test fun truncatedJsonAlwaysKeepsTheOriginalEvenWhenItParses() {
        listOf("{\"a\":1}", "{\"a\":", "[1,2]").forEach {
            val source = PreviewText(it, true)
            assertSame(source, prettyPreviewJson(source))
        }
    }

    @Test fun invalidJsonKeepsItsOriginalSpacingAndContent() {
        listOf("", "  not json  ", "{\"a\":}", "{\"a\":1,}", "[1,,2]", "{a:1}", "[1", "true false",
            "{\"value\":\"line\nbreak\"}", "{\"value\":NaN}").forEach {
            val source = PreviewText(it, false)
            assertSame(it, source, prettyPreviewJson(source))
        }
    }

    @Test fun rawJsonStringControlCharactersKeepTheOriginalObject() {
        (0..0x1F).forEach { code ->
            val control = code.toChar()
            listOf(
                "{\"value\":\"before${control}after\"}",
                "{\"key${control}\":1}",
                "{\"value\":\"before\\${control}after\"}"
            ).forEach { raw ->
                val source = PreviewText(raw, false)
                assertSame("control=$code", source, prettyPreviewJson(source))
            }
        }
    }

    @Test fun escapedJsonControlCharactersStillAllowPrettyPrinting() {
        listOf("\\u0000", "\\b", "\\t", "\\n", "\\u000b", "\\f", "\\r", "\\u001f").forEach { escaped ->
            val source = PreviewText("{\"value\":\"$escaped\"}", false)
            val result = prettyPreviewJson(source)
            assertNotEquals(escaped, source.text, result.text)
            assertFalse(result.truncated)
            assertEquals(escaped, Json.parseToJsonElement(source.text), Json.parseToJsonElement(result.text))
        }
    }

    @Test fun maliciousJsonDepthReturnsTheOriginalWithoutRecursiveParsing() {
        val source = PreviewText("[".repeat(20_000) + "0" + "]".repeat(20_000), false)
        assertSame(source, prettyPreviewJson(source))
        val mixed = PreviewText("{\"a\":".repeat(1_000) + "0" + "}".repeat(1_000), false)
        assertSame(mixed, prettyPreviewJson(mixed))
    }

    @Test fun jsonDepthLimitIsInclusive() {
        val within = PreviewText("[".repeat(64) + "0" + "]".repeat(64), false)
        val beyond = PreviewText("[".repeat(65) + "0" + "]".repeat(65), false)
        assertNotEquals(within.text, prettyPreviewJson(within).text)
        assertSame(beyond, prettyPreviewJson(beyond))
    }

    @Test fun bracketsAndEscapedQuotesInsideJsonStringsDoNotCountAsDepth() {
        val source = PreviewText("{\"value\":\"\\\"" + "[{".repeat(1_000) + "\\\\end\"}", false)
        val result = prettyPreviewJson(source)
        assertNotEquals(source.text, result.text)
        assertEquals(Json.parseToJsonElement(source.text), Json.parseToJsonElement(result.text))
    }

    @Test fun oversizedJsonInputsAreNotParsedOrReformatted() {
        listOf(
            "\"" + "x".repeat(PreviewLimits.TEXT_BYTES) + "\"",
            "\"" + "中".repeat(800_000) + "\""
        ).forEach {
            val source = PreviewText(it, false)
            assertSame(source, prettyPreviewJson(source))
        }
    }

    @Test fun jsonAtTheUtf8ByteBudgetCanRemainComplete() {
        val source = PreviewText("\"" + "x".repeat(PreviewLimits.TEXT_BYTES - 2) + "\"", false)
        val result = prettyPreviewJson(source)
        assertEquals(source, result)
        assertEquals(PreviewLimits.TEXT_BYTES, result.text.toByteArray().size)
        assertFalse(result.truncated)
    }

    @Test fun prettyPrintingCannotExpandNestedJsonPastTheOutputBudget() {
        val source = PreviewText("[".repeat(64) + "0,".repeat(20_000) + "0" + "]".repeat(64), false)
        assertTrue(source.text.toByteArray().size < PreviewLimits.TEXT_BYTES)
        assertSame(source, prettyPreviewJson(source))
        assertFalse(source.truncated)
    }

    @Test fun alreadyFormattedJsonIsStillBoundedAndPreservesValues() {
        val source = PreviewText("{\n  \"a\": [\n    1,\n    2\n  ]\n}\n", false)
        val result = prettyPreviewJson(source)
        assertFalse(result.truncated)
        assertTrue(result.text.toByteArray().size <= PreviewLimits.TEXT_BYTES)
        assertEquals(Json.parseToJsonElement(source.text), Json.parseToJsonElement(result.text))
    }

    @Test fun emptyTableHasNoInventedRow() {
        assertEquals(PreviewTable(emptyList(), false), parsePreviewTable(PreviewText("", false), ','))
        assertEquals(PreviewTable(emptyList(), true), parsePreviewTable(PreviewText("", true), ','))
    }

    @Test fun csvQuotesProtectCommasAndDoubleQuotesAreUnescaped() {
        val result = table("name,value\r\n\"a,b\",\"say \"\"hello\"\"\"\r\n")
        assertEquals(listOf(listOf("name", "value"), listOf("a,b", "say \"hello\"")), result.rows)
        assertFalse(result.truncated)
    }

    @Test fun csvQuotedFieldsCanContainCrLfAndStandaloneNewlines() {
        val result = table("\"line1\r\nline2\nline3\rline4\",tail\r\nnext,row")
        assertEquals(listOf(listOf("line1\r\nline2\nline3\rline4", "tail"), listOf("next", "row")), result.rows)
        assertFalse(result.truncated)
    }

    @Test fun mixedRowTerminatorsAndBlankRowsArePreserved() {
        val result = table("a\r\n\r\nb\rc\n")
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b"), listOf("c")), result.rows)
        assertFalse(result.truncated)
    }

    @Test fun emptyAndTrailingCsvFieldsAreNotDiscarded() {
        assertEquals(listOf(listOf("", "")), table(",").rows)
        assertEquals(listOf(listOf("a", "", "")), table("a,,\n").rows)
        assertEquals(listOf(listOf("", "")), table("\"\",\"\"").rows)
        assertEquals(listOf(listOf("")), table("\n").rows)
        assertFalse(table("\"\",\"\"\r\n").truncated)
    }

    @Test fun aQuotedQuoteAndQuotedWhitespaceArePreserved() {
        val result = table("\"\"\"quoted\"\"\",\" space \"")
        assertEquals(listOf(listOf("\"quoted\"", " space ")), result.rows)
        assertFalse(result.truncated)
    }

    @Test fun tsvAndSemicolonDelimitersUseTheSameQuoteStateMachine() {
        assertEquals(listOf(listOf("a\tb", "c"), listOf("next", "row")),
            parsePreviewTable(PreviewText("\"a\tb\"\tc\r\nnext\trow", false), '\t').rows)
        assertEquals(listOf(listOf("a;b", "c,d")),
            parsePreviewTable(PreviewText("\"a;b\";c,d", false), ';').rows)
    }

    @Test fun invalidDelimitersAreRejected() {
        listOf('"', '\r', '\n', '\u0000').forEach {
            assertThrows(IllegalArgumentException::class.java) { parsePreviewTable(PreviewText("a,b", false), it) }
        }
    }

    @Test fun incompleteQuotedFieldsKeepTheirContentAndMarkTruncation() {
        val result = table("header,value\n\"a,b\ncontinued")
        assertEquals(listOf(listOf("header", "value"), listOf("a,b\ncontinued")), result.rows)
        assertTrue(result.truncated)
        assertEquals(PreviewTable(listOf(listOf("a\"")), true), table("\"a\"\""))
    }

    @Test fun malformedQuotesAreNotSilentlyTreatedAsValidCsv() {
        assertEquals(PreviewTable(listOf(listOf("a\"b", "c")), true), table("a\"b,c"))
        assertEquals(PreviewTable(listOf(listOf("ax", "b")), true), table("\"a\"x,b"))
    }

    @Test fun callerTextTruncationPropagatesToTheTable() {
        val result = parsePreviewTable(PreviewText("a,b\n1,2", true), ',')
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), result.rows)
        assertTrue(result.truncated)
    }

    @Test fun tableRowLimitIsInclusiveWithOrWithoutATrailingNewline() {
        val csv = (0 until PreviewLimits.CSV_ROWS).joinToString("\n") { "row$it,value" }
        listOf(csv, "$csv\n", csv.replace("\n", "\r\n") + "\r\n").forEach {
            val result = table(it)
            assertEquals(200, result.rows.size)
            assertEquals(listOf("row199", "value"), result.rows.last())
            assertFalse(result.truncated)
        }
    }

    @Test fun extraRowsAreNotReturnedAndMarkTruncation() {
        val csv = (0..PreviewLimits.CSV_ROWS).joinToString("\n") { "row$it,value" }
        val result = table(csv)
        assertEquals(200, result.rows.size)
        assertEquals(listOf("row199", "value"), result.rows.last())
        assertTrue(result.truncated)
    }

    @Test fun newlinesInsideQuotesDoNotConsumeTheRowBudget() {
        val csv = (0 until PreviewLimits.CSV_ROWS).joinToString("\n") { "\"row$it\ncontinuation\",value" }
        val result = table(csv)
        assertEquals(200, result.rows.size)
        assertEquals(listOf("row199\ncontinuation", "value"), result.rows.last())
        assertFalse(result.truncated)
    }

    @Test fun tableColumnLimitIsInclusive() {
        val expected = (0 until PreviewLimits.CSV_COLUMNS).map { "c$it" }
        val result = table(expected.joinToString(","))
        assertEquals(listOf(expected), result.rows)
        assertFalse(result.truncated)
    }

    @Test fun extraColumnsAreSkippedWithoutShiftingFollowingRows() {
        val csv = (0..PreviewLimits.CSV_COLUMNS).joinToString(",") { "c$it" } + ",\"ignored\ncontinued\"\nnext,ok"
        val result = table(csv)
        assertEquals(2, result.rows.size)
        assertEquals((0 until 50).map { "c$it" }, result.rows.first())
        assertEquals(listOf("next", "ok"), result.rows.last())
        assertTrue(result.truncated)
    }

    @Test fun anExtraEmptyColumnStillMarksTruncation() {
        val csv = (0 until PreviewLimits.CSV_COLUMNS).joinToString(",") { "c$it" }
        val result = table("$csv,")
        assertEquals(50, result.rows.single().size)
        assertTrue(result.truncated)
    }

    @Test fun tableCellCharacterLimitIsInclusive() {
        val value = "x".repeat(8_192)
        assertEquals(PreviewTable(listOf(listOf(value, "tail")), false), table("$value,tail"))
    }

    @Test fun longCellsAreBoundedWhileQuoteAndRowParsingContinue() {
        val csv = "\"" + "x".repeat(9_000) + "\ncontinued\",tail\nnext,row"
        val result = table(csv)
        assertEquals(listOf(listOf("x".repeat(8_192), "tail"), listOf("next", "row")), result.rows)
        assertTrue(result.truncated)
    }

    @Test fun cellTruncationDoesNotLeaveAHalfSurrogatePair() {
        val result = table("x".repeat(8_191) + "🙂,tail")
        assertEquals(listOf(listOf("x".repeat(8_191), "tail")), result.rows)
        assertTrue(result.truncated)
        assertFalse(result.rows.first().first().last().isHighSurrogate())
    }

    @Test fun tableDirectInputStillHasAnAggregateByteBudget() {
        val row = List(50) { "x".repeat(8_192) }.joinToString(",") + "\n"
        val result = table(row.repeat(6))
        assertTrue(result.truncated)
        assertTrue(result.rows.size <= 200)
        assertTrue(result.rows.all { it.size <= 50 && it.all { cell -> cell.length <= 8_192 } })
        assertTrue(result.rows.sumOf { it.sumOf { cell -> cell.toByteArray().size } } <= PreviewLimits.TEXT_BYTES)
    }

    @Test fun tableByteBudgetHandlesMultibyteUnicodeWithoutReplacement() {
        val result = table("\"" + "中".repeat(800_000) + "\"")
        assertEquals(listOf(listOf("中".repeat(8_192))), result.rows)
        assertTrue(result.truncated)
        assertFalse(result.rows.single().single().contains('\uFFFD'))
    }

    @Test fun listsAnEmptyZip() {
        assertEquals(PreviewArchive(emptyList(), false), listPreviewArchive(zip(emptyList())))
    }

    @Test fun archiveListingPreservesOrderNamesSizesAndDirectories() {
        val file = zip(listOf("folder/" to "", "folder/a.txt" to "hello", "中文.txt" to "🙂"))
        val result = listPreviewArchive(file)
        assertEquals(listOf(
            PreviewArchiveEntry("folder/", 0, true),
            PreviewArchiveEntry("folder/a.txt", 5, false),
            PreviewArchiveEntry("中文.txt", 4, false)
        ), result.entries)
        assertFalse(result.truncated)
    }

    @Test fun archiveEntryLimitIsInclusive() {
        val file = zip((0 until PreviewLimits.ARCHIVE_ENTRIES).map { "entry$it.txt" to "" })
        val result = listPreviewArchive(file)
        assertEquals(2_000, result.entries.size)
        assertEquals("entry1999.txt", result.entries.last().name)
        assertFalse(result.truncated)
    }

    @Test fun archiveListingStopsAtTwoThousandEntries() {
        val file = zip((0..PreviewLimits.ARCHIVE_ENTRIES).map { "entry$it.txt" to "" })
        val result = listPreviewArchive(file)
        assertEquals(2_000, result.entries.size)
        assertEquals("entry1999.txt", result.entries.last().name)
        assertTrue(result.truncated)
    }

    @Test fun longArchiveNamesAreBoundedAndMarkTheArchiveTruncated() {
        val name = "n".repeat(5_000)
        val result = listPreviewArchive(zip(listOf(name to "data")))
        assertEquals(1_024, result.entries.single().name.length)
        assertEquals("n".repeat(1_023) + "\u2026", result.entries.single().name)
        assertEquals(4L, result.entries.single().size)
        assertTrue(result.truncated)
    }

    @Test fun exactArchiveNameLimitIsNotTruncated() {
        val name = "n".repeat(1_024)
        val result = listPreviewArchive(zip(listOf(name to "")))
        assertEquals(name, result.entries.single().name)
        assertFalse(result.truncated)
    }

    @Test fun archiveNameTruncationDoesNotSplitASurrogatePair() {
        val name = "n".repeat(1_022) + "🙂tail"
        val result = listPreviewArchive(zip(listOf(name to "")))
        assertEquals("n".repeat(1_022) + "\u2026", result.entries.single().name)
        assertTrue(result.truncated)
    }

    @Test fun traversalAbsoluteAndHtmlEntryNamesRemainPlainMetadata() {
        val escape = File(temporary.root, "outside.txt")
        val absolute = File(temporary.root, "absolute.txt")
        val names = listOf("../outside.txt", "../../escape.txt", absolute.absolutePath, "folder/../../../outside.txt",
            "<script>alert(1)</script>.txt", "folder\\..\\outside.txt")
        val file = zip(names.map { it to "payload" }, temporary.newFolder("archives"))
        val before = temporary.root.walkTopDown().map { it.relativeTo(temporary.root).path }.toSet()
        val result = listPreviewArchive(file)
        val after = temporary.root.walkTopDown().map { it.relativeTo(temporary.root).path }.toSet()
        assertEquals(names, result.entries.map { it.name })
        assertEquals(before, after)
        assertFalse(escape.exists())
        assertFalse(absolute.exists())
        assertFalse(File(file.parentFile, "folder").exists())
        assertFalse(result.truncated)
    }

    @Test fun largeDeclaredEntrySizesAreOnlyListedNotAllocatedOrInflated() {
        val file = zip(listOf("huge.bin" to "small"))
        val bytes = file.readBytes()
        val central = (0 until bytes.size - 4).first { index ->
            bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4B.toByte() &&
                bytes[index + 2] == 0x01.toByte() && bytes[index + 3] == 0x02.toByte()
        }
        RandomAccessFile(file, "rw").use {
            it.seek(central.toLong() + 24)
            it.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F))
        }
        val result = listPreviewArchive(file)
        assertEquals(listOf(PreviewArchiveEntry("huge.bin", Int.MAX_VALUE.toLong(), false)), result.entries)
        assertFalse(result.truncated)
        assertTrue(file.length() < 1_024)
    }

    @Test fun listingDoesNotReadCorruptCompressedPayloads() {
        val file = zip(listOf("payload.bin" to "x".repeat(10_000)))
        val bytes = file.readBytes()
        val nameBytes = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)
        val extraBytes = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)
        RandomAccessFile(file, "rw").use {
            it.seek((30 + nameBytes + extraBytes).toLong())
            it.writeByte(0xFF)
        }
        assertEquals(PreviewArchive(listOf(PreviewArchiveEntry("payload.bin", 10_000, false)), false), listPreviewArchive(file))
    }

    @Test fun archivesBeyondTheFileBudgetAreRejected() {
        val file = temporary.newFile("oversized.zip")
        RandomAccessFile(file, "rw").use { it.setLength(PreviewLimits.FILE_BYTES + 1) }
        assertThrows(IOException::class.java) { listPreviewArchive(file) }
    }

    @Test fun invalidAndMissingArchivesPropagateIoFailures() {
        val invalid = temporary.newFile("invalid.zip").apply { writeText("not a zip") }
        assertThrows(IOException::class.java) { listPreviewArchive(invalid) }
        assertThrows(IOException::class.java) { listPreviewArchive(File(temporary.root, "missing.zip")) }
    }

    private fun table(text: String): PreviewTable = parsePreviewTable(PreviewText(text, false), ',')

    private fun zip(entries: List<Pair<String, String>>, directory: File = temporary.root): File {
        val file = File.createTempFile("preview-", ".zip", directory)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private class CountingStream(
        private val content: ByteArray,
        private val chunk: Int = Int.MAX_VALUE,
        private val zeroBulkReads: Boolean = false
    ) : InputStream() {
        var bytesRead = 0
            private set
        var calls = 0
            private set
        var maximumRequest = 0
            private set
        var closed = false
            private set

        override fun read(): Int {
            calls++
            if (bytesRead == content.size) return -1
            return content[bytesRead++].toInt() and 0xFF
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            calls++
            maximumRequest = maxOf(maximumRequest, length)
            if (zeroBulkReads) return 0
            if (bytesRead == content.size) return -1
            val count = minOf(length, chunk, content.size - bytesRead)
            content.copyInto(target, offset, bytesRead, bytesRead + count)
            bytesRead += count
            return count
        }

        override fun close() { closed = true }
    }
}
