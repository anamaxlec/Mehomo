package dev.memoh.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * How a server is addressed. The three shapes differ in more than a base URL —
 * their auth scheme and WebSocket handshake differ too, so the distinction is a
 * type rather than a flag.
 */
enum class ServerKind {
    /** A self-hosted Memoh backend reached directly or behind a reverse proxy. */
    SelfHosted,

    /** The official platform at app.memoh.net: cookie session + ws tickets. */
    OfficialCloud,
}

/**
 * A resolved server endpoint.
 *
 * @param baseUrl REST root, without a trailing slash.
 * @param apiPrefix prefix that must be prepended to API paths (empty for
 *        self-hosted, `/api/v1` for the platform API).
 * @param memohPrefix prefix for the Memoh proxy on the platform (unused when
 *        self-hosted).
 */
data class ServerEndpoint(
    val kind: ServerKind,
    val origin: String,
    val apiPrefix: String,
    val memohPrefix: String,
) {
    /** Absolute URL for a REST path such as `/bots`. */
    fun api(path: String): String = origin + apiPrefix + ensureLeadingSlash(path)

    /** Absolute URL for a Memoh-service path such as `/bots/{id}/web/ws`. */
    fun memoh(path: String): String = origin + memohPrefix + ensureLeadingSlash(path)

    /** Absolute URL for a path on the platform API (teams, auth, ws-tickets). */
    fun platform(path: String): String = origin + "/api/v1" + ensureLeadingSlash(path)

    val isCloud: Boolean get() = kind == ServerKind.OfficialCloud

    companion object {
        const val CLOUD_ORIGIN = "https://app.memoh.net"
        const val CLOUD_API_PREFIX = "/api/v1"
        const val CLOUD_MEMOH_PREFIX = "/api/memoh"

        val OfficialCloud = ServerEndpoint(
            kind = ServerKind.OfficialCloud,
            origin = CLOUD_ORIGIN,
            apiPrefix = CLOUD_API_PREFIX,
            memohPrefix = CLOUD_MEMOH_PREFIX,
        )

        private fun ensureLeadingSlash(path: String): String =
            if (path.startsWith("/")) path else "/$path"
    }
}

/** Outcome of normalising what the user typed. */
sealed interface UrlParseResult {
    data class Ok(val endpoint: ServerEndpoint) : UrlParseResult
    data class Invalid(val reason: String) : UrlParseResult
}

/**
 * Turns user input into a usable endpoint.
 *
 * Rules, all of which exist because a real deployment needed them:
 *  - a missing scheme becomes https (cleartext is refused in release builds);
 *  - `http://` is rejected outright rather than silently upgraded, so the user
 *    learns their server is not reachable the way they think it is;
 *  - a trailing slash is trimmed so paths never double up;
 *  - the platform origin is recognised and pinned to its fixed prefixes, since
 *    probing it for `/api` would break its routing.
 */
object ServerUrl {

    fun parse(input: String): UrlParseResult {
        val raw = input.trim()
        if (raw.isEmpty()) return UrlParseResult.Invalid("请输入服务器地址")

        val withScheme = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> return UrlParseResult.Invalid("仅支持 HTTPS，请使用 https://")
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }

        val parsed = withScheme.toHttpUrlOrNull()
            ?: return UrlParseResult.Invalid("地址格式无效")

        if (!parsed.isHttps) return UrlParseResult.Invalid("仅支持 HTTPS")

        val host = parsed.host
        if (host.isEmpty() || !host.contains('.')) {
            return UrlParseResult.Invalid("请输入完整域名或 IP")
        }

        // The platform origin is pinned: it has a fixed route structure and must
        // never be probed or have /api appended.
        if (host.equals("app.memoh.net", ignoreCase = true) ||
            host.equals("memoh.net", ignoreCase = true) ||
            host.endsWith(".memoh.net", ignoreCase = true)
        ) {
            return UrlParseResult.Ok(ServerEndpoint.OfficialCloud)
        }

        val port = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
        val path = parsed.encodedPath.trimEnd('/')

        // A path that already ends in /api is kept; anything else is the bare
        // origin and the caller probes for the /api suffix if a request 404s.
        val origin = "${parsed.scheme}://$host$port"

        return UrlParseResult.Ok(
            ServerEndpoint(
                kind = ServerKind.SelfHosted,
                origin = origin,
                apiPrefix = path,
                memohPrefix = path,
            ),
        )
    }

    /**
     * Candidate endpoints to try for self-hosted input, in order. The first is
     * exactly what the user typed; the second appends `/api`, which is how the
     * official nginx image exposes the backend.
     */
    fun candidates(input: String): List<ServerEndpoint> {
        val first = parse(input)
        if (first !is UrlParseResult.Ok) return emptyList()
        val endpoint = first.endpoint
        if (endpoint.isCloud || endpoint.apiPrefix.isNotEmpty()) return listOf(endpoint)
        return listOf(
            endpoint,
            endpoint.copy(apiPrefix = "/api", memohPrefix = "/api"),
        )
    }
}
