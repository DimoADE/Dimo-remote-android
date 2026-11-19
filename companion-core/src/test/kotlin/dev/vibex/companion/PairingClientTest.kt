package dev.vibex.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingClientTest {
    @Test
    fun connectRequestSendsAccountTokenWhenPresent() {
        val transport = ScriptedTransport(listOf(HttpExchange(201, CONNECT_PENDING)))
        val result = PairingClient(transport).requestConnect(
            HostOrigin.parse("https://relay.example.com/t/host-1"),
            deviceName = "Pixel",
            accountToken = "  account-secret  ",
        )
        assertEquals("req-1", result.requestId)
        assertEquals("4821", result.sasCode)
        val body = transport.requests.single().body!!
        assertTrue(body.contains("\"account_token\":\"account-secret\""))
        assertTrue(body.contains("\"replace_existing_mobile\":false"))
    }

    @Test
    fun connectRequestOmitsAccountTokenWhenAbsent() {
        for (token in listOf(null, "   ")) {
            val transport = ScriptedTransport(listOf(HttpExchange(201, CONNECT_PENDING)))
            PairingClient(transport).requestConnect(HostOrigin.parse("http://192.168.1.5:17891"), accountToken = token)
            assertFalse(transport.requests.single().body!!.contains("account_token"))
        }
    }

    @Test
    fun connectRequestMapsAccountLinkReasons() {
        val cases = mapOf(
            403 to AccountLinkFailure.AccountMismatch,
            401 to AccountLinkFailure.AccountTokenInvalid,
            409 to AccountLinkFailure.HostAccountSignedOut,
            503 to AccountLinkFailure.VerificationUnavailable,
        )
        for ((status, failure) in cases) {
            val transport = ScriptedTransport(listOf(HttpExchange(status, errorEnvelope("""{"reason":"${failure.reason}"}"""))))
            val error = assertFailsWith<AccountLinkException> {
                PairingClient(transport).requestConnect(HostOrigin.parse("http://192.168.1.5:17891"), accountToken = "t")
            }
            assertEquals(failure, error.failure)
        }
    }

    @Test
    fun connectRequestKeepsMobileConflictAndGenericFailures() {
        val conflict = ScriptedTransport(
            listOf(HttpExchange(409, errorEnvelope("""{"reason":"mobile_device_conflict","existing_device_name":"iPhone"}"""))),
        )
        val error = assertFailsWith<MobileDeviceConflictException> {
            PairingClient(conflict).requestConnect(HostOrigin.parse("http://192.168.1.5:17891"), accountToken = "t")
        }
        assertEquals("iPhone", error.existingDeviceName)

        val unknown = ScriptedTransport(listOf(HttpExchange(500, errorEnvelope("""{"reason":"something_else"}"""))))
        assertFailsWith<PairingException> {
            PairingClient(unknown).requestConnect(HostOrigin.parse("http://192.168.1.5:17891"))
        }
        assertEquals(null, AccountLinkFailure.fromReason("mobile_device_conflict"))
    }

    @Test
    fun pollConnectReturnsCredentialOnApproval() {
        val transport = ScriptedTransport(
            listOf(
                HttpExchange(
                    200,
                    """{"request_id":"req-1","host_id":"host-1","sas_code":"4821","status":"approved","expires_at":"2026-11-12T00:00:00Z","credential":{"device_id":"11111111-1111-1111-1111-111111111111","access_token":"device-token","scopes":["conversation.read"]}}""",
                ),
            ),
        )
        val result = PairingClient(transport).pollConnect(HostOrigin.parse("http://192.168.1.5:17891"), "req-1")
        assertEquals("approved", result.status)
        assertEquals("device-token", result.credential?.access_token)
        assertTrue(transport.requests.single().url.endsWith("/api/v1/auth/connect-requests/req-1"))
    }

    @Test
    fun requestConnectReturnsImmediateCredentialWhenSameAccountAutoApproved() {
        val transport = ScriptedTransport(
            listOf(
                HttpExchange(
                    201,
                    """{"request_id":"req-auto","host_id":"host-1","host_name":"Mac Studio","sas_code":"7392","status":"approved","expires_at":"2026-11-14T12:05:00Z","credential":{"device_id":"22222222-2222-2222-2222-222222222222","access_token":"instant-device-token","scopes":["conversation.read","conversation.write"]}}""",
                ),
            ),
        )
        val result = PairingClient(transport).requestConnect(
            HostOrigin.parse("http://192.168.1.5:17891"),
            deviceName = "Pixel 9",
            accountToken = "same-account-jwt",
        )
        assertEquals("approved", result.status)
        assertEquals("7392", result.sasCode)
        assertEquals("Mac Studio", result.hostName)
        assertEquals("instant-device-token", result.credential?.access_token)
    }

    @Test
    fun redeemStoresCompanionCredentialAndNegotiatesV1() {
        val transport = ScriptedTransport(
            listOf(
                HttpExchange(
                    201,
                    """{"device_id":"11111111-1111-1111-1111-111111111111","access_token":"device-token","scopes":["conversation.read","conversation.write","offline.read"]}""",
                ),
                HttpExchange(
                    200,
                    """{"server_version":"0.1.3","protocol_version":"1.0","minimum_client_version":"1.0","capabilities":["conversation.attach"]}""",
                ),
            ),
        )
        val session = PairingClient(transport).redeem(
            HostOrigin.parse("http://127.0.0.1:3080"),
            "one-time-secret",
            "Pixel",
        )
        assertEquals("device-token", session.credential.access_token)
        assertEquals("1.0", session.capabilities.protocol_version)
        assertEquals(2, transport.requests.size)
        assertEquals("POST", transport.requests[0].method)
        assertTrue(transport.requests[0].url.endsWith("/api/v1/auth/pairings/redeem"))
        assertTrue(transport.requests[0].body!!.contains("\"pairing_token\":\"one-time-secret\""))
        assertEquals("GET", transport.requests[1].method)
        assertEquals("Bearer device-token", transport.requests[1].headers["authorization"])
    }

    @Test
    fun redeemRejectsHostAdminScopes() {
        val transport = ScriptedTransport(
            listOf(
                HttpExchange(
                    201,
                    """{"device_id":"11111111-1111-1111-1111-111111111111","access_token":"device-token","scopes":["conversation.read","host.admin"]}""",
                ),
            ),
        )
        val error = assertFailsWith<PairingException> {
            PairingClient(transport).redeem(
                HostOrigin.parse("https://host.example"),
                "token",
            )
        }
        assertTrue(error.message!!.contains("host.admin"))
    }

    @Test
    fun requestConnectReportsAnOfflineDesktopBehindTheRelay() {
        for (status in listOf(502, 503, 504)) {
            val transport = ScriptedTransport(listOf(HttpExchange(status, "")))
            assertFailsWith<HostUnreachableException> {
                PairingClient(transport).requestConnect(
                    HostOrigin.parse("https://dimo-ai.com/t/host-1"),
                    accountToken = "acct-token",
                )
            }
        }
    }

    @Test
    fun hostOriginRejectsCredentialsInUrl() {
        assertFailsWith<IllegalArgumentException> {
            HostOrigin.parse("http://user:secret@127.0.0.1:3080")
        }
    }

    @Test
    fun e2eeHandshakeAesGcmAndClearSessionSucceed() {
        val origin = "https://dimo-ai.com/t/host-e2ee-android"
        val session = E2eeSessionManager.ingestHandshake(
            origin,
            "sess-android-01",
            E2eeSessionManager.clientPublicKeyBase64,
        )
        assertTrue(session != null)
        assertTrue(E2eeSessionManager.isE2eeActive(origin))

        val plain = """{"operation_id":"op-1","args":{"text":"hello e2ee"}}"""
        val cipherB64 = E2eeSessionManager.encryptString(session.aesKey, E2eeSessionManager.AAD_REQ, plain)
        val decrypted = E2eeSessionManager.decryptString(session.aesKey, E2eeSessionManager.AAD_REQ, cipherB64)
        assertEquals(plain, decrypted)

        E2eeSessionManager.clearSession("$origin/api/v1/capabilities")
        assertFalse(E2eeSessionManager.isE2eeActive(origin))
        assertEquals(null, E2eeSessionManager.session(origin))

        val rehandshaken = E2eeSessionManager.ingestHandshake(
            origin,
            "sess-android-02",
            E2eeSessionManager.clientPublicKeyBase64,
        )
        assertEquals("sess-android-02", rehandshaken?.sessionId)
        assertTrue(E2eeSessionManager.isE2eeActive(origin))
    }

    @Test
    fun migratesLegacyRelayOriginsAndRedirectsRememberedBases() {
        assertEquals(
            "https://dimo-ai.com/t/host-123",
            PairingClient.migrateLegacyRelayOrigin("https://vibex.xforever.xin/t/host-123"),
        )
        assertEquals(
            "https://dimo-ai.com/t/host-123",
            PairingClient.migrateLegacyRelayOrigin("http://vibex.xforever.xin/t/host-123/"),
        )
        assertEquals(
            "https://dimo-ai.com/t/host-123",
            PairingClient.migrateLegacyRelayOrigin("https://dimo-ai.com/t/host-123"),
        )
        assertEquals(
            "https://custom-relay.example.com/t/host-123",
            PairingClient.migrateLegacyRelayOrigin("https://custom-relay.example.com/t/host-123"),
        )
        assertEquals(
            "http://192.168.1.8:17891",
            PairingClient.migrateLegacyRelayOrigin("http://192.168.1.8:17891"),
        )

        PairingClient.rememberRelayBase("https://vibex.xforever.xin/t/host-123")
        PairingClient.rememberRelayBase("http://vibex.xforever.xin")
        val bases = PairingClient.knownRelayBases(listOf("https://vibex.xforever.xin/t/host-456"))
        assertTrue(bases.contains("https://dimo-ai.com"))
        assertTrue(bases.none { it.contains("vibex.xforever.xin") })

        val rendezvous = PairingClient.resolveHostRendezvous("host-abc")
        assertTrue(rendezvous.contains("https://dimo-ai.com/t/host-abc"))
        assertTrue(DiscoveredLanHost("https://dimo-ai.com", "h1", "Mac").isCloudService)
        assertTrue(DiscoveredLanHost("https://vibex.xforever.xin", "h1", "Mac").isCloudService)
    }

    @Test
    fun filtersCellularAndVirtualNetworkInterfacesFromLanScan() {
        val blocked = listOf(
            null,
            "",
            "lo",
            "lo0",
            "rmnet0",
            "rmnet_data0",
            "ccmni0",
            "ccmni1",
            "pdp_ip0",
            "clat4",
            "v4-rmnet_data0",
            "dummy0",
            "utun0",
            "tun0",
            "tap0",
            "ppp0",
            "ipsec0",
            "wg0",
            "tailscale0",
            "clash0",
            "en_mihomo",
            "surge-utun",
        )
        for (iface in blocked) {
            assertFalse(PairingClient.isScannableLanInterfaceName(iface), "expected blocked: $iface")
        }

        val allowed = listOf(
            "wlan0",
            "wlan1",
            "eth0",
            "en0",
            "ap0",
            "swlan0",
            "wifi0",
            "rndis0",
            "usb0",
        )
        for (iface in allowed) {
            assertTrue(PairingClient.isScannableLanInterfaceName(iface), "expected allowed: $iface")
        }
    }
}

private data class RecordedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

private class ScriptedTransport(
    private val responses: List<HttpExchange>,
) : HttpTransport {
    val requests = mutableListOf<RecordedRequest>()
    private var index = 0

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpExchange {
        requests += RecordedRequest(method, url, headers, body)
        return responses[index++]
    }
}

private const val CONNECT_PENDING =
    """{"request_id":"req-1","host_id":"host-1","host_name":"Studio","sas_code":"4821","status":"pending","expires_at":"2026-11-12T00:00:00Z"}"""

private fun errorEnvelope(details: String): String =
    """{"code":"forbidden","message":"refused","retryable":false,"operation_id":"op-1","details":$details}"""
