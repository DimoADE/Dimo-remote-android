package dev.vibex.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionPolicyTest {
    @Test
    fun backoffDoublesAndCaps() {
        assertEquals(800L, ConnectionPolicy.backoffBaseMs(0))
        assertEquals(1600L, ConnectionPolicy.backoffBaseMs(1))
        assertEquals(3200L, ConnectionPolicy.backoffBaseMs(2))
        assertEquals(15_000L, ConnectionPolicy.backoffBaseMs(10))
        assertEquals(15_000L, ConnectionPolicy.backoffBaseMs(1000))
    }

    @Test
    fun jitterStaysInBand() {
        assertEquals(640L, ConnectionPolicy.backoffMs(0, 0.0))
        assertTrue(ConnectionPolicy.backoffMs(0, 0.999) <= 960L)
        assertTrue(ConnectionPolicy.backoffMs(30, 1.0) <= 18_000L)
    }

    @Test
    fun recoveringIsDebounced() {
        assertFalse(ConnectionPolicy.shouldShowRecovering(null, 10_000))
        assertFalse(ConnectionPolicy.shouldShowRecovering(9_000, 10_000))
        assertTrue(ConnectionPolicy.shouldShowRecovering(8_000, 10_000))
    }

    @Test
    fun singlePollFailureIsNotDegraded() {
        assertFalse(ConnectionPolicy.pollDegraded(1))
        assertTrue(ConnectionPolicy.pollDegraded(3))
    }

    @Test
    fun httpRefreshGating() {
        assertFalse(ConnectionPolicy.shouldRunHttpRefresh(false, ConnectionState.Online, 99_999))
        assertFalse(ConnectionPolicy.shouldRunHttpRefresh(true, ConnectionState.Offline, 99_999))
        assertFalse(ConnectionPolicy.shouldRunHttpRefresh(true, ConnectionState.Online, 5_000))
        assertTrue(ConnectionPolicy.shouldRunHttpRefresh(true, ConnectionState.Online, 12_000))
    }

    @Test
    fun logIsBounded() {
        val log = ConnectionLog(3)
        repeat(10) { log.add("e$it") }
        assertEquals(listOf("e7", "e8", "e9"), log.snapshot())
    }
}
