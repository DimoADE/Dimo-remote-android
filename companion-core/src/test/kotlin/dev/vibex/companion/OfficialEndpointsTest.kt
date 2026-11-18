package dev.vibex.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficialEndpointsTest {
    @Test
    fun defaultsUseTheMigratedApexHosts() {
        assertEquals("https://dimo-ai.com", OfficialEndpoints.SITE_BASE_URL)
        assertEquals("https://model.dimo-ai.com", OfficialEndpoints.MODEL_BASE_URL)
        assertFalse(OfficialEndpoints.SITE_BASE_URL.contains("www."))
        assertFalse(OfficialEndpoints.SITE_BASE_URL.contains("xforever"))
        assertEquals("https://dimo-ai.com", OfficialEndpoints.siteBase(null))
        assertEquals("https://dimo-ai.com", OfficialEndpoints.siteBase("   "))
    }

    @Test
    fun retiredOfficialHostsRewriteOnlyAtUseTime() {
        assertEquals("https://dimo-ai.com", OfficialEndpoints.siteBase("https://vibex.xforever.xin"))
        assertEquals(
            "https://dimo-ai.com/t/host-1/",
            OfficialEndpoints.compatOfficialUrl("http://www.vibex.xforever.xin/t/host-1/"),
        )
        assertEquals(
            "https://dimo-ai.com/docs/connect-host",
            OfficialEndpoints.compatOfficialUrl("https://www.dimo-ai.com/docs/connect-host"),
        )
        assertEquals(
            "https://dimo-ai.com:8443/t/host-1",
            OfficialEndpoints.compatOfficialUrl("https://vibex.xforever.xin:8443/t/host-1"),
        )
        assertEquals(
            "https://model.dimo-ai.com/v1/chat",
            OfficialEndpoints.compatOfficialUrl("http://model.xforever.xin/v1/chat"),
        )
        assertEquals(
            "https://model.dimo-ai.com/v1/chat",
            OfficialEndpoints.compatOfficialUrl("https://www.model.xforever.xin/v1/chat"),
        )
    }

    @Test
    fun customAddressesAndUnrelatedLinksStay() {
        val unchanged = listOf(
            "https://relay.cn.example/t/alpha",
            "http://192.168.1.8:3080",
            "https://box.ts.net",
            "https://github.com/Xircth/VibeX",
            "https://github.com/DimoADE/Dimo",
            "https://schemas.vibex.dev/remote-protocol/v1/schema.json",
            "https://model.dimo-ai.com/v1",
            "https://accounts.example.com/login",
            "not a url",
        )
        unchanged.forEach { assertEquals(it, OfficialEndpoints.compatOfficialUrl(it), it) }
        assertEquals("https://relay.cn.example", OfficialEndpoints.siteBase("https://relay.cn.example/"))
        assertEquals(
            "https://my-host.example:8443",
            OfficialEndpoints.officialRelayBase("https://my-host.example:8443"),
        )
    }

    @Test
    fun blankRelayOverrideFallsBackToTheMigratedSite() {
        assertEquals("https://dimo-ai.com", OfficialEndpoints.officialRelayBase(null))
        assertEquals("https://dimo-ai.com", OfficialEndpoints.officialRelayBase("  "))
        assertEquals("https://dimo-ai.com", OfficialEndpoints.officialRelayBase("https://vibex.xforever.xin/"))
    }

    @Test
    fun mobileReleaseCheckUsesTheMigratedSiteUnlessOverridden() {
        assertEquals(
            "https://dimo-ai.com/api/mobile/releases/latest?platform=android&version=0.4.5",
            OfficialEndpoints.mobileReleaseCheckUrl("0.4.5"),
        )
        assertEquals(
            "https://dimo-ai.com/api/mobile/releases/latest?platform=android&version=0.4.8",
            OfficialEndpoints.mobileReleaseCheckUrl(" 0.4.8 ", "https://vibex.xforever.xin"),
        )
        assertEquals(
            "https://updates.example/api/mobile/releases/latest?platform=android&version=1.2.3",
            OfficialEndpoints.mobileReleaseCheckUrl("1.2.3", "https://updates.example"),
        )
    }

    @Test
    fun hostOriginRewritesRetiredOfficialHostsAndKeepsCustomOrigins() {
        assertEquals("https://dimo-ai.com/t/host-1", HostOrigin.parse("https://vibex.xforever.xin/t/host-1").value)
        assertEquals("https://dimo-ai.com/t/host-1", HostOrigin.parse("http://www.vibex.xforever.xin/t/host-1/").value)
        assertEquals("http://192.168.1.8:3080", HostOrigin.parse("http://192.168.1.8:3080").value)
        assertEquals("https://box.ts.net", HostOrigin.parse("https://box.ts.net").value)
        assertEquals("https://relay.cn.example/t/alpha", HostOrigin.parse("https://relay.cn.example/t/alpha").value)
        assertFalse(HostOrigin.parse("https://vibex.xforever.xin/t/host-1").isLocalNetwork)
        assertTrue(HostOrigin.parse("http://192.168.1.8:3080").isLocalNetwork)
    }

    @Test
    fun credentialedLegacyOriginIsStillRejected() {
        assertFailsWith<IllegalArgumentException> {
            HostOrigin.parse("https://user:secret@vibex.xforever.xin/t/host-1")
        }
    }

    @Test
    fun rememberedLegacyRelayBaseDoesNotStayOnTheRetiredHost() {
        PairingClient.rememberRelayBase("https://vibex.xforever.xin/t/host-1")
        PairingClient.rememberRelayBase("https://custom-relay.example/t/kept")
        val bases = PairingClient.knownRelayBases()
        assertTrue(bases.contains("https://dimo-ai.com"))
        assertTrue(bases.contains("https://custom-relay.example"))
        assertTrue(bases.none { it.contains("xforever") || it.contains("www.dimo-ai.com") })
    }
}
