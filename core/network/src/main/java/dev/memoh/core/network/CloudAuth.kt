package dev.memoh.core.network

import dev.memoh.core.model.EmailCodeSendRequest
import dev.memoh.core.model.EmailCodeSendResponse
import dev.memoh.core.model.EmailCodeVerifyRequest
import dev.memoh.core.model.EmailCodeVerifyResponse
import dev.memoh.core.model.TeamMembership
import dev.memoh.core.model.TeamsResponse
import dev.memoh.core.model.VerifyMfaRequest
import dev.memoh.core.model.WsTicketResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * The official platform at app.memoh.net.
 *
 * It differs from a self-hosted server in three ways that each need code:
 *  - authentication is an HttpOnly cookie session, not a bearer token, so the
 *    client must keep a cookie jar shared by REST and WebSocket handshakes;
 *  - the API lives under `/api/v1` while the Memoh service lives under
 *    `/api/memoh`, so requests target two prefixes on one origin;
 *  - WebSockets cannot authenticate with a cookie, so every handshake — including
 *    reconnects — must first exchange a one-shot ticket.
 */
class CloudAuth(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: ServerEndpoint = ServerEndpoint.OfficialCloud,
    storage: CloudCookieStorage? = null,
) {
    private val cookieJar = MemoryCookieJar(storage)

    val http: OkHttpClient = client.newBuilder()
        .cookieJar(cookieJar)
        .build()

    /** The team (workspace) the session is scoped to, sent as `X-Team-Id`. */
    @Volatile
    var teamId: String? = null

    val hasSession: Boolean get() = cookieJar.hasCookies()

    /**
     * The most recently minted ticket.
     *
     * Tickets are single-use, so this is refreshed by [refreshTicket] between
     * handshakes rather than reused; the socket reads it synchronously because
     * OkHttp's handshake cannot await.
     */
    @Volatile
    private var cachedTicket: String? = null

    fun currentTicket(): String? = cachedTicket

    /** Mints a fresh ticket and caches it for the next handshake. */
    suspend fun refreshTicket(): String {
        val ticket = wsTicket()
        cachedTicket = ticket
        return ticket
    }

    // -- email code ---------------------------------------------------------

    suspend fun sendEmailCode(email: String, locale: String? = null): EmailCodeSendResponse =
        postPlatform(
            path = "/auth/email-code/send",
            body = json.encodeToString(
                EmailCodeSendRequest.serializer(),
                EmailCodeSendRequest(email = email, preferredLocale = locale),
            ),
        )

    /**
     * Verifies the code. When the account has MFA enabled the response carries
     * `mfaRequired` and the caller must continue with [verifyMfa].
     */
    suspend fun verifyEmailCode(email: String, code: String): EmailCodeVerifyResponse =
        postPlatform(
            path = "/auth/email-code/verify",
            body = json.encodeToString(
                EmailCodeVerifyRequest.serializer(),
                EmailCodeVerifyRequest(email = email, code = code),
            ),
        )

    suspend fun verifyMfa(mfaToken: String, totpCode: String) {
        platformCall(
            path = "/auth/verify-mfa",
            method = "POST",
            body = json.encodeToString(
                VerifyMfaRequest.serializer(),
                VerifyMfaRequest(mfaToken = mfaToken, totpCode = totpCode),
            ),
            deserializer = Unit.serializer(),
        )
    }

    // -- session ------------------------------------------------------------

    suspend fun teams(): List<TeamMembership> =
        getPlatform<TeamsResponse>("/teams").teams

    /**
     * Exchanges the cookie session for a one-shot WebSocket ticket.
     *
     * Must be called before *every* handshake, reconnects included: the ticket
     * is short-lived and single-use. `Origin` is required by the platform.
     */
    suspend fun wsTicket(): String {
        val response: WsTicketResponse = postPlatform(path = "/ws-tickets", body = "{}")
        return response.ticket
    }

    /** Full WebSocket URL for a bot, including a freshly minted ticket. */
    suspend fun webSocketUrl(botId: String): String {
        return ticketedSocketUrl("/bots/$botId/web/ws")
    }

    internal suspend fun ticketedSocketUrl(path: String): String {
        val ticket = wsTicket()
        return endpoint.memoh(path).toHttpUrl().newBuilder()
            .addQueryParameter("ticket", ticket)
            .apply { teamId?.let { addQueryParameter("team_id", it) } }
            .build().toString()
    }

    internal suspend fun runtimeDisplayRequest(session: dev.memoh.core.model.RuntimeDisplaySession): Request {
        val url = endpoint.origin.toHttpUrl().newBuilder()
            .addPathSegments("api/runtime-gateway/v1/display").addPathSegment(session.sessionId)
            .addQueryParameter("ticket", wsTicket()).build()
        val protocol = "memoh-runtime-token." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(session.token.toByteArray(Charsets.UTF_8))
        return authorize(Request.Builder().url(url)).header("Sec-WebSocket-Protocol", protocol).build()
    }

    fun authorize(builder: Request.Builder): Request.Builder = builder
        .header("Origin", endpoint.origin)
        .apply { teamId?.let { header("X-Team-Id", it) } }

    fun clearSession() {
        cookieJar.clear()
        teamId = null
        cachedTicket = null
    }

    // -- plumbing -----------------------------------------------------------

    private suspend inline fun <reified T> getPlatform(path: String): T =
        platformCall(path, "GET", null, serializer<T>())

    private suspend inline fun <reified T> postPlatform(path: String, body: String): T =
        platformCall(path, "POST", body, serializer<T>())

    /**
     * Performs the blocking HTTP exchange.
     *
     * Runs on [Dispatchers.IO]: OkHttp's synchronous `execute()` throws
     * [android.os.NetworkOnMainThreadException] when a caller happens to be on
     * the main thread, which every ViewModel scope is by default.
     */
    private suspend fun <T> platformCall(
        path: String,
        method: String,
        body: String?,
        deserializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(endpoint.platform(path))
            .header("Accept", "application/json")
            // The platform rejects cross-origin calls without this.
            .header("Origin", endpoint.origin)
        teamId?.let { builder.header("X-Team-Id", it) }

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(
                (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE),
            )
            else -> throw IllegalArgumentException("unsupported method $method")
        }

        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, text, "$method $path failed: ${response.code}")
            }
            if (deserializer.descriptor.serialName == "kotlin.Unit") {
                @Suppress("UNCHECKED_CAST") (Unit as T)
            } else json.decodeFromString(deserializer, text)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Session cookies can be restored from the encrypted account store. */
interface CloudCookieStorage {
    fun loadCloudCookies(): List<Cookie>
    fun saveCloudCookies(cookies: List<Cookie>)
}

internal class MemoryCookieJar(private val storage: CloudCookieStorage? = null) : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        storage?.loadCloudCookies()?.filter { it.expiresAt > System.currentTimeMillis() }
            ?.groupBy { it.domain }?.forEach { (host, cookies) -> store[host] = cookies.toMutableList() }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        cookies.forEach { incoming ->
            val list = store.getOrPut(incoming.domain) { mutableListOf() }
            synchronized(list) {
                val index = list.indexOfFirst { it.name == incoming.name && it.path == incoming.path }
                if (index >= 0) list[index] = incoming else list.add(incoming)
            }
        }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.flatMap { list ->
            synchronized(list) {
                list.removeAll { it.expiresAt <= now }
                list.filter { it.matches(url) }
            }
        }
    }

    fun hasCookies(): Boolean = store.values.any { list ->
        synchronized(list) { list.any { it.expiresAt > System.currentTimeMillis() } }
    }

    fun clear() { store.clear(); persist() }

    private fun persist() {
        storage?.saveCloudCookies(store.values.flatMap { synchronized(it) { it.toList() } })
    }
}
