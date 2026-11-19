package dev.vibex.companion

/** Pure reconnect / debounce policy so connection behaviour is unit-testable. */
object ConnectionPolicy {
    const val BACKOFF_BASE_MS = 800L
    const val BACKOFF_MAX_MS = 15_000L
    const val RECOVERING_DEBOUNCE_MS = 1_500L
    const val WS_PING_INTERVAL_MS = 15_000L
    const val HTTP_REFRESH_INTERVAL_MS = 12_000L
    const val POLL_FAILURES_BEFORE_DEGRADED = 3
    const val RECONNECT_ATTEMPTS_BEFORE_OFFLINE = 3

    /** Base delay (no jitter): 0.8s, 1.6s, 3.2s ... capped at 15s. [attempt] is 0-based. */
    fun backoffBaseMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        return (BACKOFF_BASE_MS shl shift).coerceAtMost(BACKOFF_MAX_MS)
    }

    /** Delay with +-20% jitter; [random01] in [0,1). Never exceeds the cap by more than jitter of cap. */
    fun backoffMs(attempt: Int, random01: Double): Long {
        val base = backoffBaseMs(attempt)
        val factor = 0.8 + 0.4 * random01.coerceIn(0.0, 1.0)
        return (base * factor).toLong().coerceAtLeast(1L)
    }

    /** Only surface "Recovering" once the link has been down longer than the debounce window. */
    fun shouldShowRecovering(downSinceMs: Long?, nowMs: Long): Boolean =
        downSinceMs != null && nowMs - downSinceMs >= RECOVERING_DEBOUNCE_MS

    /** A single failed poll is noise; only a streak counts as degraded. */
    fun pollDegraded(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= POLL_FAILURES_BEFORE_DEGRADED

    /** Whether the periodic HTTP refresh should run. */
    fun shouldRunHttpRefresh(
        foreground: Boolean,
        state: ConnectionState,
        sinceLastMs: Long,
    ): Boolean = foreground && state == ConnectionState.Online && sinceLastMs >= HTTP_REFRESH_INTERVAL_MS

    /** After this many failed reconnects show Offline instead of Recovering. */
    fun reconnectGivesOffline(failedAttempts: Int): Boolean =
        failedAttempts >= RECONNECT_ATTEMPTS_BEFORE_OFFLINE
}

/** Bounded in-memory ring of connection transitions for diagnostics. */
class ConnectionLog(private val capacity: Int = 100) {
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()
}
