package com.aicode.feature.player.data

import okhttp3.HttpUrl
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal fun relayUrl(prefix: String, url: String, referer: String) = "${prefix}relay?u=${encodeQuery(url)}" + if (referer.isEmpty()) "" else "&ref=${encodeQuery(referer)}"
internal const val LOCAL_MANIFEST_HOST = "player-workspace.invalid"
internal fun localManifestBase(path: String): HttpUrl = HttpUrl.Builder().scheme("https").host(LOCAL_MANIFEST_HOST)
    .addPathSegment(if (path.startsWith("~/")) "tilde" else "absolute")
    .apply { path.removePrefix("~/").trimStart('/').split('/').forEach { addPathSegment(it) } }.build()
internal fun localManifestPath(segments: List<String>): String {
    val prefix = when (segments.firstOrNull()) { "tilde" -> "~/"; "absolute" -> "/"; else -> throw PlayerHttpException(403, "Invalid local manifest path") }
    if (segments.drop(1).any { '/' in it || '\\' in it }) throw PlayerHttpException(403, "Invalid local manifest segment")
    return containerPath(prefix + segments.drop(1).joinToString("/"))
}
private val dashPlaceholder = Regex("\\$(?:Number|Time|Bandwidth|RepresentationID)(?:%0[1-9][0-9]*d)?\\$")
private fun validateLocalManifestUrl(url: HttpUrl) {
    val safe = url.newBuilder().encodedPath(dashPlaceholder.replace(url.encodedPath, "template")).build()
    localManifestPath(safe.pathSegments)
}
internal fun localManifestUrl(prefix: String, url: HttpUrl): String {
    validateLocalManifestUrl(url)
    return "${prefix}files${url.encodedPath}" + (url.encodedQuery?.let { "?$it" } ?: "")
}
internal fun rewriteHls(text: String, finalUrl: HttpUrl, prefix: String, referer: String, local: Boolean = false): String {
    fun proxy(value: String, allowInlineKey: Boolean = false): String {
        val raw = value.trim()
        if (allowInlineKey && raw.startsWith("data:application/octet-stream;base64,", true)) {
            val bytes = runCatching { java.util.Base64.getDecoder().decode(raw.substringAfter(',')) }.getOrNull()
            if (raw.length > 128 || bytes?.size !in setOf(16, 32)) throw PlayerHttpException(502, "Invalid inline media key")
            return raw
        }
        val resolved = finalUrl.resolve(raw) ?: throw PlayerHttpException(502, "Invalid playlist URI")
        if (local && resolved.host == LOCAL_MANIFEST_HOST) return localManifestUrl(prefix, resolved)
        return relayUrl(prefix, remoteUrl(resolved.toString()).toString(), referer)
    }
    val uri = Regex("(?i)(\\bURI\\s*=\\s*)(?:\"([^\"]*)\"|'([^']*)'|([^,\\s]+))")
    return buildString {
        text.lineSequence().forEachIndexed { index, line ->
            if (index > 20000 || length > 16 * 1024 * 1024) throw PlayerHttpException(502, "Playlist expansion limit")
            if (index > 0) append('\n')
            val trimmed = line.trim()
            append(when {
                trimmed.isEmpty() -> line
                trimmed.startsWith('#') -> uri.replace(line) { match ->
                    val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]!!.value
                    "${match.groupValues[1]}\"${proxy(value, trimmed.startsWith("#EXT-X-KEY:") || trimmed.startsWith("#EXT-X-SESSION-KEY:"))}\""
                }
                else -> proxy(trimmed)
            })
        }
        if (length > 16 * 1024 * 1024) throw PlayerHttpException(502, "Playlist expansion limit")
    }
}
internal class DashMappings(private val prefix: String, private val maxMappings: Int = 128, private val local: Boolean = false,
    private val remoteMappings: DashMappings? = null) {
    internal data class Origin(val url: HttpUrl, val referer: String)
    private val origins = linkedMapOf<String, Origin>()
    fun validate(url: HttpUrl) {
        if (local && url.host == LOCAL_MANIFEST_HOST) validateLocalManifestUrl(url) else remoteUrl(url.toString())
    }
    @Synchronized fun proxy(url: HttpUrl, referer: String): String {
        if (local && url.host == LOCAL_MANIFEST_HOST) return localManifestUrl(prefix, url)
        remoteMappings?.let { return it.proxy(url, referer) }
        remoteUrl(url.toString())
        val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val entry = origins.entries.firstOrNull { it.value.url == origin && it.value.referer == referer }
        val id = entry?.key ?: run {
            if (origins.size >= maxMappings) throw PlayerHttpException(503, "DASH origin limit")
            sessionToken().take(24).also { origins[it] = Origin(origin, referer) }
        }
        return "${prefix}dash/$id${url.encodedPath}" + (url.encodedQuery?.let { "?$it" } ?: "")
    }
    @Synchronized fun resolve(route: String, rawQuery: String): Pair<String, String> {
        val rest = route.removePrefix("dash/")
        val origin = origins[rest.substringBefore('/')] ?: throw PlayerHttpException(404, "Unknown DASH origin")
        if ('/' !in rest) throw PlayerHttpException(404, "Missing DASH path")
        val target = origin.url.toString() + rest.substringAfter('/') + if (rawQuery.isEmpty()) "" else "?$rawQuery"
        return remoteUrl(target).toString() to origin.referer
    }
    @Synchronized fun clear() = origins.clear()
}
internal fun rewriteDash(bytes: ByteArray, finalUrl: HttpUrl, mappings: DashMappings, referer: String): ByteArray {
    val syntax = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
    if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(syntax)) throw PlayerHttpException(502, "DASH DTD is unsupported")
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true; isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    }
    val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External XML entity") } }
    val document = builder.parse(ByteArrayInputStream(bytes))
    fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    fun Element.tag() = localName ?: tagName.substringAfter(':')
    fun merge(parent: Element?, child: Element): Element {
        val result = child.cloneNode(true) as Element
        if (parent != null) {
            for (i in 0 until parent.attributes.length) {
                val attr = parent.attributes.item(i)
                if (!result.hasAttribute(attr.nodeName)) result.setAttribute(attr.nodeName, attr.nodeValue)
            }
            val present = result.children().map { it.tag() }.toSet()
            parent.children().filter { it.tag() !in present }.forEach { result.appendChild(it.cloneNode(true)) }
        }
        return result
    }
    var elements = 0
    fun walk(node: Element, inheritedBase: HttpUrl, inheritedSegments: Map<String, Element>, depth: Int = 0) {
        if (++elements > 50000 || depth > 64) throw PlayerHttpException(502, "DASH structure limit")
        val children = node.children()
        val bases = children.filter { it.tag() == "BaseURL" }
        val base = bases.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let {
            inheritedBase.resolve(it) ?: throw PlayerHttpException(502, "Invalid DASH BaseURL")
        } ?: inheritedBase
        mappings.validate(base)
        val segments = inheritedSegments.toMutableMap()
        val segmentTags = setOf("SegmentTemplate", "SegmentList", "SegmentBase")
        children.filter { it.tag() in segmentTags }.forEach {
            val inherited = segments[it.tag()]; segments.clear(); segments[it.tag()] = merge(inherited, it)
        }
        if (node.tag() == "Representation") {
            children.filter { it.tag() in segmentTags }.forEach(node::removeChild)
            segments.values.forEach { node.appendChild(it.cloneNode(true)) }
        }
        bases.forEachIndexed { index, element -> if (index == 0) element.textContent = mappings.proxy(base, referer) else node.removeChild(element) }
        if (bases.isEmpty() && node.tag() == "MPD") {
            val element = document.createElementNS(node.namespaceURI, "BaseURL")
            element.textContent = mappings.proxy(base, referer); node.insertBefore(element, node.firstChild)
        }
        val fields = when (node.tag()) {
            "SegmentTemplate" -> listOf("media", "initialization", "index", "bitstreamSwitching")
            "SegmentURL" -> listOf("media", "index")
            "Initialization", "RepresentationIndex", "BitstreamSwitching" -> listOf("sourceURL")
            else -> emptyList()
        }
        fields.forEach { name -> if (node.hasAttribute(name)) {
            val target = base.resolve(node.getAttribute(name)) ?: throw PlayerHttpException(502, "Invalid DASH URI")
            node.setAttribute(name, mappings.proxy(target, referer))
        } }
        if (node.tag() in setOf("Location", "PatchLocation", "Laurl", "LA_URL") && node.textContent.trim().isNotEmpty()) {
            val target = base.resolve(node.textContent.trim()) ?: throw PlayerHttpException(502, "Invalid DASH URI")
            node.textContent = mappings.proxy(target, referer)
        }
        if (node.tag() == "UTCTiming" && node.getAttribute("schemeIdUri").contains("http-"))
            base.resolve(node.getAttribute("value"))?.let { node.setAttribute("value", mappings.proxy(it, referer)) }
        node.children().filter { it.tag() != "BaseURL" }.forEach { walk(it, base, segments, depth + 1) }
    }
    walk(document.documentElement, finalUrl, emptyMap())
    val transformers = TransformerFactory.newInstance().apply {
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "") }
    }
    val output = StringWriter()
    transformers.newTransformer().transform(DOMSource(document), StreamResult(output))
    return output.toString().toByteArray()
}
