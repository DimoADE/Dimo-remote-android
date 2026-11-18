package dev.vibex.companion

import java.net.URI
import java.net.URLEncoder

/**
 * Defaults for the official site, account API, relay, and mobile release check.
 *
 * A caller-supplied host that is not one of the retired official hosts is returned unchanged.
 * Retired hosts are rewritten only while a URL is being used. This object does not read or
 * write saved profiles, account tokens, or device credentials.
 */
object OfficialEndpoints {
    const val SITE_BASE_URL = "https://dimo-ai.com"
    const val MODEL_BASE_URL = "https://model.dimo-ai.com"
    const val MOBILE_RELEASE_PATH = "/api/mobile/releases/latest"

    private const val SITE_HOST = "dimo-ai.com"
    private const val MODEL_HOST = "model.dimo-ai.com"

    /**
     * Relay base used when the account has not assigned one.
     * `VIBEX_RELAY_URL`, when set, wins over [SITE_BASE_URL]. A retired official host in that
     * override is still rewritten; every other host is kept.
     */
    fun officialRelayBase(environmentOverride: String? = System.getenv("VIBEX_RELAY_URL")): String =
        siteBase(environmentOverride)

    /** Site origin for login, account API, and in-app pages. Blank means [SITE_BASE_URL]. */
    fun siteBase(override: String? = null): String {
        val raw = override?.trim().orEmpty()
        val chosen = if (raw.isEmpty()) SITE_BASE_URL else raw
        return compatOfficialUrl(chosen).trimEnd('/')
    }

    /**
     * URL of `GET /api/mobile/releases/latest` from the shipped 0.4.8 client.
     * This tree has no update checker; callers that add one must use this instead of the retired host.
     */
    fun mobileReleaseCheckUrl(versionName: String, siteOverride: String? = null): String {
        val version = URLEncoder.encode(versionName.trim(), Charsets.UTF_8.name())
        return "${siteBase(siteOverride)}$MOBILE_RELEASE_PATH?platform=android&version=$version"
    }

    /**
     * Maps only the retired official site and model hosts onto their replacements.
     * Path, query, and a non-default port are preserved. Other URLs, including user relays
     * and unrelated links, are returned unchanged.
     */
    fun compatOfficialUrl(raw: String): String {
        val trimmed = raw.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return trimmed
        }
        val host = uri.host?.lowercase() ?: return trimmed
        val replacement = when (host) {
            "vibex.xforever.xin", "www.vibex.xforever.xin", "www.dimo-ai.com" -> SITE_HOST
            "model.xforever.xin", "www.model.xforever.xin" -> MODEL_HOST
            else -> return trimmed
        }
        val user = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        val port = when (uri.port) {
            -1, 80, 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "https://$user$replacement$port$path$query$fragment"
    }
}
