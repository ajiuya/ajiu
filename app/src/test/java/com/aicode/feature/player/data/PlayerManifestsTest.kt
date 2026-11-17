package com.aicode.feature.player.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class PlayerManifestsTest {
    @Test fun hlsResolvesFinalRedirectLocationAndCarriesRefToKeysAndChildren() {
        val ref = "https://watch.example/show?id=9"
        val final = "https://cdn.example/redirected/nested/master.m3u8?sig=one".toHttpUrl()
        val rewritten = rewriteHls("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin?k=a+b\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/list.m3u8\"\n../video/seg.ts\n", final, "/TOKEN/", ref)
        assertTrue(rewritten.contains(relayUrl("/TOKEN/", "https://cdn.example/redirected/key.bin?k=a+b", ref)))
        assertTrue(rewritten.contains(relayUrl("/TOKEN/", "https://cdn.example/redirected/nested/init.mp4", ref)))
        assertTrue(rewritten.contains(relayUrl("/TOKEN/", "https://cdn.example/redirected/nested/audio/list.m3u8", ref)))
        assertTrue(rewritten.contains(relayUrl("/TOKEN/", "https://cdn.example/redirected/video/seg.ts", ref)))
        assertFalse(rewritten.contains("URI=\"https://"))
    }

    @Test fun hlsAllowsBoundedInlineKeysButRejectsScriptAndDataSegments() {
        val key = "data:application/octet-stream;base64,AAAAAAAAAAAAAAAAAAAAAA=="
        val text = rewriteHls("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"$key\"\nseg.ts", "https://cdn.example/m.m3u8".toHttpUrl(), "/T/", "")
        assertTrue(text.contains("URI=\"$key\""))
        assertThrows(PlayerHttpException::class.java) { rewriteHls("#EXTM3U\n$key", "https://cdn.example/m.m3u8".toHttpUrl(), "/T/", "") }
        assertThrows(PlayerHttpException::class.java) { rewriteHls("#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/html,hi\"", "https://cdn.example/m.m3u8".toHttpUrl(), "/T/", "") }
        assertThrows(PlayerHttpException::class.java) { rewriteHls("#EXTM3U\njavascript:alert(1)", "https://cdn.example/m.m3u8".toHttpUrl(), "/T/", "") }
    }

    @Test fun inheritedDashTemplatesResolveAtRepresentationAndRemainSubstitutable() {
        val mappings = DashMappings("/TOKEN/")
        val input = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>../media/</BaseURL><Period><AdaptationSet>
            <SegmentTemplate timescale="1000" initialization="init-${'$'}RepresentationID${'$'}.mp4" media="seg-${'$'}Number%05d${'$'}-${'$'}Time${'$'}.m4s?sig=a%2Bb"><SegmentTimeline><S t="0" d="10"/></SegmentTimeline></SegmentTemplate>
            <Representation id="v1"><BaseURL>video/</BaseURL><SegmentTemplate startNumber="7"/></Representation>
            <Representation id="v2"><BaseURL>other/</BaseURL></Representation>
            </AdaptationSet></Period></MPD>"""
        val result = rewriteDash(input.toByteArray(), "https://cdn.example/manifests/main.mpd".toHttpUrl(), mappings, "https://watch.example/")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(result.inputStream())
        val representations = doc.getElementsByTagNameNS("*", "Representation")
        assertEquals(2, representations.length)
        for (i in 0 until representations.length) {
            val rep = representations.item(i) as Element
            val template = rep.getElementsByTagNameNS("*", "SegmentTemplate").item(0) as Element
            val media = template.getAttribute("media")
            assertTrue(media, media.contains("${'$'}Number%05d${'$'}"))
            assertTrue(media, media.contains("${'$'}Time${'$'}"))
            assertFalse(media, media.contains("%24"))
            val substituted = media.replace("${'$'}Number%05d${'$'}", "00007").replace("${'$'}Time${'$'}", "123")
            val (url, ref) = mappings.resolve(substituted.substringBefore('?').removePrefix("/TOKEN/"), substituted.substringAfter('?', ""))
            assertEquals("https://cdn.example/media/${if (i == 0) "video" else "other"}/seg-00007-123.m4s?sig=a%2Bb", url)
            assertEquals("https://watch.example/", ref)
            assertEquals("1000", template.getAttribute("timescale"))
            assertEquals(1, template.getElementsByTagNameNS("*", "S").length)
            val init = template.getAttribute("initialization").replace("${'$'}RepresentationID${'$'}", rep.getAttribute("id"))
            assertTrue(mappings.resolve(init.removePrefix("/TOKEN/"), "").first.endsWith("init-v${i + 1}.mp4"))
        }
    }

    @Test fun segmentListRelativeBaseAndByteRangesSurvive() {
        val mappings = DashMappings("/T/")
        val input = """<MPD><Period><AdaptationSet><SegmentList><Initialization sourceURL="init.mp4" range="0-99"/><SegmentURL media="chunk.m4s" mediaRange="100-199" index="index.sidx"/></SegmentList><Representation id="v"><BaseURL>../video/</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        val result = rewriteDash(input.toByteArray(), "https://cdn.example/a/main.mpd".toHttpUrl(), mappings, "")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(result.inputStream())
        val rep = doc.getElementsByTagName("Representation").item(0) as Element
        val init = rep.getElementsByTagName("Initialization").item(0) as Element
        assertEquals("0-99", init.getAttribute("range"))
        assertEquals("https://cdn.example/video/init.mp4", mappings.resolve(init.getAttribute("sourceURL").removePrefix("/T/"), "").first)
        val segment = rep.getElementsByTagName("SegmentURL").item(0) as Element
        assertEquals("100-199", segment.getAttribute("mediaRange"))
        assertEquals("https://cdn.example/video/chunk.m4s", mappings.resolve(segment.getAttribute("media").removePrefix("/T/"), "").first)
        assertEquals("https://cdn.example/video/index.sidx", mappings.resolve(segment.getAttribute("index").removePrefix("/T/"), "").first)
    }

    @Test fun dashRootRelativeAndAbsoluteReferencesStayInsideToken() {
        val mappings = DashMappings("/T/")
        val input = """<MPD><Period><AdaptationSet><Representation><SegmentTemplate media="/s/${'$'}Number${'$'}.m4s" initialization="https://other.example/init.mp4"/></Representation></AdaptationSet></Period></MPD>"""
        val out = rewriteDash(input.toByteArray(), "https://cdn.example/a/main.mpd".toHttpUrl(), mappings, "").toString(Charsets.UTF_8)
        assertFalse(out, out.contains("media=\"/s/"))
        assertFalse(out, out.contains("initialization=\"https://"))
        assertTrue(out, out.contains("${'$'}Number${'$'}"))
    }

    @Test fun localHlsRewritesRelativeKeysChildrenAndSegmentsToCapabilityFiles() {
        val rewritten = rewriteHls("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\nchild/list.m3u8\na%20b.ts\nhttps://cdn.example/remote.ts\n",
            localManifestBase("~/workspace/media/list.m3u8"), "/T/", "https://watch.example/", local = true)
        assertTrue(rewritten.contains("URI=\"/T/files/tilde/workspace/key.bin\""))
        assertTrue(rewritten.contains("/T/files/tilde/workspace/media/child/list.m3u8"))
        assertTrue(rewritten.contains("/T/files/tilde/workspace/media/a%20b.ts"))
        assertTrue(rewritten.contains(relayUrl("/T/", "https://cdn.example/remote.ts", "https://watch.example/")))
        assertEquals("~/workspace/media/a b.ts", localManifestPath(listOf("tilde", "workspace", "media", "a b.ts")))
        assertEquals("/media/a.ts", localManifestPath(localManifestBase("/media/a.ts").pathSegments))
    }

    @Test fun localDashKeepsFormattedTemplateAndMapsSubstitutedPathBackToWorkspace() {
        val input = """<MPD><Period><AdaptationSet><Representation id="v"><SegmentTemplate initialization="init.mp4" media="chunk-${'$'}Number%05d${'$'}.m4s?sig=a%2Bb"/></Representation></AdaptationSet></Period></MPD>"""
        val result = rewriteDash(input.toByteArray(), localManifestBase("~/workspace/media/main.mpd"), DashMappings("/T/", local = true), "")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(result.inputStream())
        val template = doc.getElementsByTagName("SegmentTemplate").item(0) as Element
        val media = template.getAttribute("media")
        assertEquals("/T/files/tilde/workspace/media/chunk-${'$'}Number%05d${'$'}.m4s?sig=a%2Bb", media)
        assertEquals("/T/files/tilde/workspace/media/init.mp4", template.getAttribute("initialization"))
        val substituted = media.replace("${'$'}Number%05d${'$'}", "00007")
        val route = sessionRoute(substituted.substringBefore('?'), "T")!!
        assertEquals("~/workspace/media/chunk-00007.m4s", localManifestPath(route.removePrefix("files/").split('/').map(::decodeQuery)))
    }

    @Test fun localDashRemoteBaseUsesResolvableSessionMappingAndRetainsReferer() {
        val input = """<MPD><BaseURL>https://cdn.example/media/</BaseURL><Period><AdaptationSet><Representation><SegmentTemplate media="chunk-${'$'}Number${'$'}.m4s"/></Representation></AdaptationSet></Period></MPD>"""
        val mappings = DashMappings("/T/", local = true)
        val result = rewriteDash(input.toByteArray(), localManifestBase("~/workspace/main.mpd"), mappings, "https://watch.example/")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(result.inputStream())
        val template = doc.getElementsByTagName("SegmentTemplate").item(0) as Element
        val route = template.getAttribute("media").replace("${'$'}Number${'$'}", "7").removePrefix("/T/")
        assertEquals("https://cdn.example/media/chunk-7.m4s" to "https://watch.example/", mappings.resolve(route, ""))
    }

    @Test fun localManifestSegmentsRejectForeignRootsSeparatorsAndControls() {
        listOf(listOf("other", "workspace", "a.ts"), listOf("tilde", "workspace", "a/b.ts"),
            listOf("absolute", "media", "a\\b.ts"), listOf("tilde", "workspace", "bad\u0005.ts")).forEach {
            assertThrows(PlayerHttpException::class.java) { localManifestPath(it) }
        }
    }

    @Test fun dashRejectsExternalEntitiesAndUnknownMapping() {
        val xml = """<!DOCTYPE MPD [<!ENTITY x SYSTEM "file:///etc/passwd">]><MPD>&x;</MPD>"""
        assertThrows(PlayerHttpException::class.java) { rewriteDash(xml.toByteArray(), "https://cdn.example/a.mpd".toHttpUrl(), DashMappings("/T/"), "") }
        assertThrows(PlayerHttpException::class.java) { rewriteDash(xml.toByteArray(Charsets.UTF_16), "https://cdn.example/a.mpd".toHttpUrl(), DashMappings("/T/"), "") }
        assertThrows(PlayerHttpException::class.java) { DashMappings("/T/").resolve("dash/unknown/a.m4s", "") }
    }
}
