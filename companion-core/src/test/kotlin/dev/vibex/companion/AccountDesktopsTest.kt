package dev.vibex.companion

import dev.vibex.remote.v1.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountDesktopsTest {
    @Test
    fun parsesLocalDesktopsFromHostsAndRecords() {
        val root = JsonValue.parse(
            """
            {
                "hosts": [
                    {"hostId":"host:alpha","hostName":"Alpha","kind":"local","osType":"macos","osArch":"arm64","agents":[],"updatedAt":"2026-11-10T08:00:00Z"},
                    {"hostId":"host:cloud-box","hostName":"Cloud","kind":"remote","osType":"linux","osArch":"x86_64","agents":[],"updatedAt":"2026-11-12T08:00:00Z"},
                    {"hostId":"host:ssh-box","hostName":"SSH","kind":"ssh","osType":"linux","osArch":"x86_64","agents":[],"updatedAt":"2026-11-12T08:00:00Z"}
                ],
                "records": [
                    {"hostId":"host:alpha","hostName":"Alpha Record","kind":"local","osType":"macos","osArch":"arm64","lastSeenAt":"2026-11-11T09:00:00Z","configSynced":true},
                    {"hostId":"beta","hostName":"Beta","kind":"local","osType":"windows","osArch":"x86_64","lastSeenAt":"2026-11-12T07:00:00Z","configSynced":false},
                    {"hostId":"host:web","hostName":"Web","kind":"web","osType":"","osArch":"","lastSeenAt":"2026-11-12T09:00:00Z","configSynced":false},
                    {"hostId":"host:","hostName":"Empty","kind":"local","lastSeenAt":"2026-11-12T10:00:00Z"}
                ]
            }
            """.trimIndent(),
        ) as JsonValue.Object

        val desktops = AccountDesktops.parse(root)

        assertEquals(listOf("beta", "alpha"), desktops.map { it.hostId })
        val alpha = desktops.last()
        assertEquals("Alpha", alpha.hostName)
        assertEquals("2026-11-11T09:00:00Z", alpha.lastSeenAt)
        assertEquals("macOS · arm64", alpha.platformBadge)
        assertEquals("Windows · x86_64", desktops.first().platformBadge)
    }

    @Test
    fun skipsDeviceLoginsWithoutAHost() {
        val root = JsonValue.parse(
            """
            {"records": [
                {"hostId":"session:42","hostName":"iPhone","kind":"local","lastSeenAt":"2026-11-12T11:00:00Z","configSynced":false},
                {"hostId":"host:local-desktop","hostName":"Local Host","kind":"local","osType":"macos","osArch":"aarch64","lastSeenAt":"2026-11-12T12:00:00Z"},
                {"hostId":"host:remote-desktop","hostName":"Local Host","kind":"local","osType":"linux","osArch":"x86_64","lastSeenAt":"2026-11-12T12:00:00Z"},
                {"hostId":"web:browser-1","hostName":"Web","kind":"local","lastSeenAt":"2026-11-12T12:00:00Z"},
                {"hostId":"profile:abc","hostName":"Profile","kind":"local","lastSeenAt":"2026-11-12T12:00:00Z"},
                {"hostId":"origin:http://localhost","hostName":"Origin","kind":"local","lastSeenAt":"2026-11-12T12:00:00Z"},
                {"hostId":"default","hostName":"Default","kind":"local","lastSeenAt":"2026-11-12T12:00:00Z"},
                {"hostId":"localhost","hostName":"Localhost","kind":"local","lastSeenAt":"2026-11-12T12:00:00Z"},
                {"hostId":"host:mac","hostName":"Mac","kind":"local","lastSeenAt":"2026-11-12T10:00:00Z","configSynced":true}
            ]}
            """.trimIndent(),
        ) as JsonValue.Object
        assertEquals(listOf("mac"), AccountDesktops.parse(root).map { it.hostId })
    }

    @Test
    fun deduplicatesGenericLocalHostEntriesKeepingOnlineOrMostRecent() {
        val staleInstall = AccountDesktop(
            hostId = "uuid-old-vibex",
            hostName = "Local Host",
            osType = "macos",
            osArch = "aarch64",
            lastSeenAt = "2026-11-10T08:00:00Z",
            cloudOnline = false,
        )
        val liveInstall = AccountDesktop(
            hostId = "uuid-live-dimo",
            hostName = "Local Host",
            osType = "macos",
            osArch = "aarch64",
            lastSeenAt = "2026-11-11T08:00:00Z",
            cloudOnline = true,
        )
        val offlineNewerGhost = AccountDesktop(
            hostId = "uuid-ghost",
            hostName = "Local Host",
            osType = "macos",
            osArch = "aarch64",
            lastSeenAt = "2026-11-12T08:00:00Z",
            cloudOnline = false,
        )

        val withLive = AccountDesktops.deduplicate(listOf(staleInstall, liveInstall, offlineNewerGhost))
        assertEquals(listOf("uuid-live-dimo"), withLive.map { it.hostId })

        val allOffline = AccountDesktops.deduplicate(
            listOf(staleInstall, liveInstall.copy(cloudOnline = false), offlineNewerGhost),
        )
        assertEquals(listOf("uuid-ghost"), allOffline.map { it.hostId })

        val allOfflineWithPaired = AccountDesktops.deduplicate(
            desktops = listOf(staleInstall, liveInstall.copy(cloudOnline = false), offlineNewerGhost),
            pairedHostIds = setOf("uuid-live-dimo"),
        )
        assertEquals(listOf("uuid-live-dimo"), allOfflineWithPaired.map { it.hostId })
    }

    @Test
    fun displayNameMapsGenericNamesToPlatformFriendlyLabelAndPreservesCustomNames() {
        assertEquals(
            "Dimo 桌面端 (Mac)",
            AccountDesktop(hostId = "h1", hostName = "Local Host", osType = "macos", osArch = "aarch64").displayName,
        )
        assertEquals(
            "Dimo 桌面端 (Windows)",
            AccountDesktop(hostId = "h2", hostName = "VibeX 电脑", osType = "windows", osArch = "x86_64").displayName,
        )
        assertEquals(
            "Dimo 桌面端 (Linux)",
            AccountDesktop(hostId = "h3", hostName = "localhost", osType = "linux", osArch = "x86_64").displayName,
        )
        assertEquals(
            "Alpha",
            AccountDesktop(hostId = "h4", hostName = "Alpha", osType = "macos", osArch = "arm64").displayName,
        )
        assertEquals(
            "Studio-MacBook",
            AccountDesktop(hostId = "h5", hostName = "Studio-MacBook.local", osType = "macos", osArch = "arm64").displayName,
        )
    }

    @Test
    fun parseToleratesMissingLists() {
        val empty = JsonValue.Object(linkedMapOf())
        assertEquals(emptyList(), AccountDesktops.parse(empty))
    }

    @Test
    fun tenantOriginUsesAssignedRelayOrDefault() {
        val fallback = "https://dimo-ai.com"
        assertEquals(
            "https://dimo-ai.com/t/alpha",
            AccountDesktops.tenantOrigin(AccountDesktops.relayBase(null, fallback), "host:alpha"),
        )
        assertEquals(
            "https://relay.cn.example/t/alpha",
            AccountDesktops.tenantOrigin(
                AccountDesktops.relayBase(AssignedRelayServer(publicOrigin = "https://relay.cn.example/"), fallback),
                "alpha",
            ),
        )
        assertEquals(
            "https://relay.cn.example:8443",
            AccountDesktops.relayBase(AssignedRelayServer(relayHost = "relay.cn.example", relayPort = 8443), fallback),
        )
        assertEquals(
            "https://relay.cn.example",
            AccountDesktops.relayBase(AssignedRelayServer(relayHost = "relay.cn.example", relayPort = 443), fallback),
        )
    }
}
