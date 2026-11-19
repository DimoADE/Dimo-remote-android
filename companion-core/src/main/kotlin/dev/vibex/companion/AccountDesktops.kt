package dev.vibex.companion

import dev.vibex.remote.v1.JsonValue
import java.util.Locale

/** A desktop Dimo install signed in to the user's account, keyed by its raw host id. */
data class AccountDesktop(
    val hostId: String,
    val hostName: String,
    val osType: String = "",
    val osArch: String = "",
    val lastSeenAt: String = "",
    val cloudOnline: Boolean = false,
) {
    val displayName: String
        get() {
            if (AccountDesktops.isGenericHostName(hostName)) {
                return when (osType.trim().lowercase(Locale.ROOT)) {
                    "macos", "darwin" -> "Dimo 桌面端 (Mac)"
                    "windows" -> "Dimo 桌面端 (Windows)"
                    "linux" -> "Dimo 桌面端 (Linux)"
                    else -> "Dimo 桌面端"
                }
            }
            val trimmed = hostName.trim()
            return if (trimmed.endsWith(".local", ignoreCase = true)) {
                trimmed.dropLast(".local".length)
            } else {
                trimmed
            }
        }

    val platformBadge: String?
        get() {
            val osLabel = when (osType.trim().lowercase(Locale.ROOT)) {
                "macos", "darwin" -> "macOS"
                "windows" -> "Windows"
                "linux" -> "Linux"
                else -> osType.trim()
            }
            val arch = osArch.trim()
            return when {
                osLabel.isNotEmpty() && arch.isNotEmpty() -> "$osLabel · $arch"
                osLabel.isNotEmpty() -> osLabel
                arch.isNotEmpty() -> arch
                else -> null
            }
        }
}

/** The relay server the account site assigned to this user, if any. */
data class AssignedRelayServer(
    val publicOrigin: String = "",
    val relayHost: String = "",
    val relayPort: Int = 0,
)

object AccountDesktops {
    private const val HOST_ID_PREFIX = "host:"
    private const val DESKTOP_KIND = "local"

    private val PLACEHOLDER_HOST_IDS = setOf(
        "local-desktop",
        "remote-desktop",
        "default",
        "localhost",
    )

    private val PLACEHOLDER_HOST_PREFIXES = listOf(
        "session:",
        "web:",
        "profile:",
        "origin:",
    )

    private val GENERIC_HOST_NAMES = setOf(
        "",
        "local host",
        "localhost",
        "vibex 电脑",
        "vibex 桌面端",
        "dimo 电脑",
        "dimo 桌面端",
        "vibex desktop",
        "dimo desktop",
    )

    fun rawHostId(hostId: String): String = hostId.trim().removePrefix(HOST_ID_PREFIX)

    fun isPlaceholderHostId(hostId: String): Boolean {
        val normalized = rawHostId(hostId).lowercase(Locale.ROOT)
        if (normalized.isEmpty() || normalized in PLACEHOLDER_HOST_IDS) return true
        return PLACEHOLDER_HOST_PREFIXES.any { normalized.startsWith(it) }
    }

    fun isGenericHostName(name: String): Boolean =
        name.trim().lowercase(Locale.ROOT) in GENERIC_HOST_NAMES

    /**
     * Desktops of the account from `/api/account/desktop/host-configs`: `hosts ∪ records`,
     * deduplicated by host id, `kind == "local"` only, most recently seen first.
     */
    fun parse(root: JsonValue.Object): List<AccountDesktop> {
        val byHostId = LinkedHashMap<String, AccountDesktop>()
        val entries = root.arrOrNull("hosts").orEmpty() + root.arrOrNull("records").orEmpty()
        for (entry in entries) {
            val obj = entry.asObject()?.let { it.objOrNull("snapshot") ?: it } ?: continue
            val kind = obj.textOrNull("kind")?.trim().orEmpty().ifEmpty { DESKTOP_KIND }
            if (kind != DESKTOP_KIND) continue
            val hostId = rawHostId(obj.textOrEmpty("hostId"))
            if (isPlaceholderHostId(hostId)) continue
            val seenAt = obj.textAny("lastSeenAt", "updatedAt").trim()
            val existing = byHostId[hostId]
            byHostId[hostId] = AccountDesktop(
                hostId = hostId,
                hostName = existing?.hostName
                    ?: obj.textOrEmpty("hostName").trim().ifEmpty { "Dimo 电脑" },
                osType = existing?.osType?.ifEmpty { null } ?: obj.textOrEmpty("osType").trim(),
                osArch = existing?.osArch?.ifEmpty { null } ?: obj.textOrEmpty("osArch").trim(),
                lastSeenAt = maxOf(existing?.lastSeenAt.orEmpty(), seenAt),
            )
        }
        return byHostId.values.sortedByDescending { it.lastSeenAt }
    }

    /**
     * Collapses stale ghost installs that report a generic host name on the same `(osType, osArch)`
     * while preserving every confirmed online computer and every custom-named computer.
     */
    fun deduplicate(
        desktops: List<AccountDesktop>,
        onlineLanHostIds: Set<String> = emptySet(),
        pairedHostIds: Set<String> = emptySet(),
    ): List<AccountDesktop> {
        val lanIds = onlineLanHostIds
            .map { rawHostId(it).lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
        val pairedIds = pairedHostIds
            .map { rawHostId(it).lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()

        fun isOnline(desktop: AccountDesktop): Boolean =
            desktop.cloudOnline || rawHostId(desktop.hostId).lowercase(Locale.ROOT) in lanIds

        fun isPaired(desktop: AccountDesktop): Boolean =
            rawHostId(desktop.hostId).lowercase(Locale.ROOT) in pairedIds

        val valid = desktops.filterNot { isPlaceholderHostId(it.hostId) }
        val (generic, custom) = valid.partition { isGenericHostName(it.hostName) }

        val dedupedGeneric = generic
            .groupBy {
                it.osType.trim().lowercase(Locale.ROOT) to it.osArch.trim().lowercase(Locale.ROOT)
            }
            .values
            .flatMap { group ->
                val online = group.filter(::isOnline)
                if (online.isNotEmpty()) {
                    online
                } else {
                    val candidates = group.filter(::isPaired).ifEmpty { group }
                    listOf(candidates.sortedByDescending { it.lastSeenAt }.first())
                }
            }

        return (custom + dedupedGeneric).sortedWith(
            compareByDescending<AccountDesktop>(::isOnline)
                .thenByDescending(::isPaired)
                .thenByDescending { it.lastSeenAt },
        )
    }

    /** Base URL of the relay that fronts the account's desktops. */
    fun relayBase(server: AssignedRelayServer?, defaultBase: String): String {
        val publicOrigin = server?.publicOrigin?.trim()?.trimEnd('/').orEmpty()
        if (publicOrigin.isNotEmpty()) {
            return if (publicOrigin.contains("://")) publicOrigin else "https://$publicOrigin"
        }
        val host = server?.relayHost?.trim().orEmpty()
        if (host.isNotEmpty()) {
            val port = server?.relayPort ?: 0
            return if (port > 0 && port != 443) "https://$host:$port" else "https://$host"
        }
        return defaultBase.trim().trimEnd('/')
    }

    fun tenantOrigin(relayBase: String, hostId: String): String =
        "${relayBase.trimEnd('/')}/t/${rawHostId(hostId)}"
}
