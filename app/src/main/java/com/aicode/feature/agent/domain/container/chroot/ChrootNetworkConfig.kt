package com.aicode.feature.agent.domain.container.chroot

import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.InetAddress

internal object ChrootNetworkConfig {
    const val FALLBACK_RESOLV_CONF = "nameserver 223.5.5.5\nnameserver 223.6.6.6\n"
    private const val MAX_NAMESERVERS = 3

    fun currentResolvConf(connectivity: ConnectivityManager): String? {
        val network = connectivity.activeNetwork ?: return null
        val properties = connectivity.getLinkProperties(network) ?: return null
        return resolvConf(properties)
    }

    fun resolvConf(properties: LinkProperties): String? = resolvConf(properties.dnsServers)

    fun resolvConf(servers: List<InetAddress>): String? {
        val addresses = servers.asSequence()
            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
            .take(MAX_NAMESERVERS)
            .toList()
        if (addresses.isEmpty()) return null
        return addresses.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" }
    }

    fun proxyEnvironment(proxy: Map<String, String>): Map<String, String> {
        if (proxy.isEmpty()) return emptyMap()
        val result = LinkedHashMap(proxy)
        val socksProxy = proxy["ALL_PROXY"]?.takeIf { it.startsWith("socks5h://", ignoreCase = true) }
        if (socksProxy != null) {
            result["all_proxy"] = socksProxy
        } else {
            proxy["HTTP_PROXY"]?.let { result["http_proxy"] = it }
            (proxy["HTTPS_PROXY"] ?: proxy["HTTP_PROXY"])?.let { result["https_proxy"] = it }
            proxy["ALL_PROXY"]?.let { result["all_proxy"] = it }
        }
        proxy["NO_PROXY"]?.let { result["no_proxy"] = it }
        return result
    }

    fun aptRuntimeConfig(proxy: Map<String, String>): String {
        // Dropping to _apt would discard the guest's Android inet group on legacy kernels.
        val lines = mutableListOf("APT::Sandbox::User \"root\";")
        fun addProxy(protocol: String, uri: String?) {
            if (uri == null) return
            require(uri.none(Char::isISOControl))
            val escaped = buildString {
                uri.toByteArray(Charsets.UTF_8).forEach { byte ->
                    val value = byte.toInt() and 255
                    if (value in 0x21..0x7e && value != 0x22 && value != 0x5c) append(value.toChar())
                    else append("%%%02X".format(value))
                }
            }
            lines += "Acquire::$protocol::Proxy \"$escaped\";"
        }
        val all = proxy["ALL_PROXY"]
        addProxy("http", proxy["HTTP_PROXY"] ?: all)
        addProxy("https", proxy["HTTPS_PROXY"] ?: proxy["HTTP_PROXY"] ?: all)
        return lines.joinToString(separator = "\n", postfix = "\n")
    }
}

internal fun chrootEnvironment(
    profileEnvironment: Map<String, String>,
    extraEnvironment: Map<String, String>,
    proxyEnvironment: Map<String, String>
): List<String> = (mapOf(
    "HOME" to "/root",
    "PATH" to "/root/.aicode/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    "TMPDIR" to "/tmp",
    "LANG" to "C.UTF-8",
    "TERM" to "xterm-256color",
    // The bind-mounted workspace can be App/FUSE-owned while the guest is root.
    // Trust only this guest path; Git-panel subrepositories add their exact cwd per command.
    "GIT_CONFIG_COUNT" to "1",
    "GIT_CONFIG_KEY_0" to "safe.directory",
    "GIT_CONFIG_VALUE_0" to "/root/workspace"
) + ChrootNetworkConfig.proxyEnvironment(proxyEnvironment) + profileEnvironment + extraEnvironment +
    mapOf("APT_CONFIG" to "/root/.aicode/apt-runtime.conf"))
    .toSortedMap()
    .map { "${it.key}=${it.value}" }
