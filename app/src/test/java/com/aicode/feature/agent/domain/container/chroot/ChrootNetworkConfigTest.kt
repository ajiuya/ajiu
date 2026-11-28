package com.aicode.feature.agent.domain.container.chroot

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootNetworkConfigTest {
    @Test
    fun resolverUsesDistinctUsableIpv4AndIpv6ServersWithinBound() {
        val configured = ChrootNetworkConfig.resolvConf(listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("192.0.2.53"),
            InetAddress.getByName("2001:db8::53"),
            InetAddress.getByName("192.0.2.53"),
            InetAddress.getByName("198.51.100.53"),
            InetAddress.getByName("203.0.113.53")
        ))

        val expectedIpv6 = requireNotNull(InetAddress.getByName("2001:db8::53").hostAddress).substringBefore('%')
        assertEquals("nameserver 192.0.2.53\nnameserver $expectedIpv6\nnameserver 198.51.100.53\n", configured)
    }

    @Test
    fun resolverDoesNotPublishLoopbackOrLinkLocalOnlyLists() {
        assertNull(ChrootNetworkConfig.resolvConf(listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("169.254.1.1"),
            InetAddress.getByName("fe80::1")
        )))
    }

    @Test
    fun resolverTracksCurrentDefaultNetworkAndReturnsNoOverrideWhenUnavailable() {
        val connectivity = mockk<ConnectivityManager>()
        val wifi = mockk<Network>()
        val mobile = mockk<Network>()
        val wifiProperties = mockk<LinkProperties>()
        val mobileProperties = mockk<LinkProperties>()
        every { connectivity.activeNetwork } returnsMany listOf(wifi, mobile, mobile, null)
        every { connectivity.getLinkProperties(wifi) } returns wifiProperties
        every { connectivity.getLinkProperties(mobile) } returnsMany listOf(mobileProperties, null)
        every { wifiProperties.dnsServers } returns listOf(InetAddress.getByName("192.0.2.53"))
        every { mobileProperties.dnsServers } returns listOf(InetAddress.getByName("198.51.100.53"))
        assertEquals("nameserver 192.0.2.53\n", ChrootNetworkConfig.currentResolvConf(connectivity))
        assertEquals("nameserver 198.51.100.53\n", ChrootNetworkConfig.currentResolvConf(connectivity))
        assertNull(ChrootNetworkConfig.currentResolvConf(connectivity))
        assertNull(ChrootNetworkConfig.currentResolvConf(connectivity))
        assertNull(ChrootNetworkConfig.resolvConf(emptyList()))
    }

    @Test
    fun aptKeepsIndependentHttpAndHttpsProxiesAndRejectsControlCharacters() {
        val proxy = mapOf("HTTP_PROXY" to "http://first.example:3128", "HTTPS_PROXY" to "http://second.example:8080")
        val config = ChrootNetworkConfig.aptRuntimeConfig(proxy)
        val unicode = ChrootNetworkConfig.aptRuntimeConfig(mapOf("HTTP_PROXY" to "http://测试:密钥@proxy.example:3128"))
        assertTrue(unicode.contains("http://%E6%B5%8B%E8%AF%95:%E5%AF%86%E9%92%A5@proxy.example:3128"))
        assertTrue(unicode.all { it.code < 128 })
        assertTrue(config.contains("Acquire::http::Proxy \"http://first.example:3128\";"))
        assertTrue(config.contains("Acquire::https::Proxy \"http://second.example:8080\";"))
        for (invalid in listOf("http://proxy.example:3128\n", "http://proxy.example:3128\r", "http://proxy.example:\u0000")) {
            assertThrows(IllegalArgumentException::class.java) {
                ChrootNetworkConfig.aptRuntimeConfig(mapOf("HTTP_PROXY" to invalid))
            }
        }
    }

    @Test
    fun httpProxyIsAvailableToCommonClientsAndApt() {
        val raw = mapOf(
            "HTTP_PROXY" to "http://user:secret@proxy.example:3128",
            "HTTPS_PROXY" to "http://user:secret@proxy.example:3128",
            "ALL_PROXY" to "http://user:secret@proxy.example:3128",
            "NO_PROXY" to "localhost,127.0.0.1"
        )

        val env = chrootEnvironment(mapOf("APT_CONFIG" to "/etc/apt/apt.conf"),
            mapOf("APT_CONFIG" to "/tmp/user-apt.conf"), raw).toSet()
        assertTrue("http_proxy=http://user:secret@proxy.example:3128" in env)
        assertTrue("https_proxy=http://user:secret@proxy.example:3128" in env)
        assertTrue("no_proxy=localhost,127.0.0.1" in env)
        assertTrue("APT_CONFIG=/root/.aicode/apt-runtime.conf" in env)
        assertEquals(
            "APT::Sandbox::User \"root\";\n" +
                "Acquire::http::Proxy \"http://user:secret@proxy.example:3128\";\n" +
                "Acquire::https::Proxy \"http://user:secret@proxy.example:3128\";\n",
            ChrootNetworkConfig.aptRuntimeConfig(raw)
        )
    }

    @Test
    fun socks5ProxyUsesRemoteDnsAndAptKeepsRootDownloadIdentity() {
        val socks = mapOf("ALL_PROXY" to "socks5h://proxy.example:1080", "NO_PROXY" to "localhost")
        val env = chrootEnvironment(emptyMap(), emptyMap(), socks).toSet()
        assertTrue("all_proxy=socks5h://proxy.example:1080" in env)
        assertTrue(env.none { it.startsWith("http_proxy=") || it.startsWith("https_proxy=") })
        assertEquals(
            "APT::Sandbox::User \"root\";\n" +
                "Acquire::http::Proxy \"socks5h://proxy.example:1080\";\n" +
                "Acquire::https::Proxy \"socks5h://proxy.example:1080\";\n",
            ChrootNetworkConfig.aptRuntimeConfig(socks)
        )

        val noProxy = chrootEnvironment(emptyMap(), emptyMap(), emptyMap()).toSet()
        assertTrue("APT_CONFIG=/root/.aicode/apt-runtime.conf" in noProxy)
        assertTrue(noProxy.none { it.endsWith("_proxy=") })
        assertEquals("APT::Sandbox::User \"root\";\n", ChrootNetworkConfig.aptRuntimeConfig(emptyMap()))
    }
}
