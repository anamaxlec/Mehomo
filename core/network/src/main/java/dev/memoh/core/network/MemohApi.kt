package dev.memoh.core.network

import dev.memoh.core.model.Bot
import dev.memoh.core.model.BotAgent
import dev.memoh.core.model.ChatModel
import dev.memoh.core.model.AgentModelCatalog
import dev.memoh.core.model.ModelProvider
import dev.memoh.core.model.CurrentUser
import dev.memoh.core.model.ListResponse
import dev.memoh.core.model.LoginRequest
import dev.memoh.core.model.LoginResponse
import dev.memoh.core.model.PingResponse
import dev.memoh.core.model.RefreshResponse
import dev.memoh.core.model.Session
import dev.memoh.core.model.SessionQueueResponse
import dev.memoh.core.model.UITurn
import dev.memoh.core.model.Workdir
import dev.memoh.core.model.WorkdirsResponse
import dev.memoh.core.model.WorkspaceTargetsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** A REST failure carrying the server's status and body for diagnostics. */
class ApiException(
    val status: Int,
    val body: String?,
    message: String,
) : IOException(message) {
    /** No usable credentials — the caller must send the user back to login. */
    val isUnauthorized: Boolean get() = status == 401 || status == 403
}

/**
 * Raised when a request stayed unauthorized even after a refresh attempt. The
 * session is genuinely gone (expired token, revoked account), as opposed to a
 * single stale-token request.
 */
class SessionExpiredException : IOException("登录已失效，请重新登录")

/**
 * A credential lease handed to a request. [generation] lets a caller detect
 * that the account changed while it was in flight, so a late response cannot
 * write into the new account's state.
 */
data class AuthLease(
    val token: String,
    val expiresAtMillis: Long?,
    val generation: Long,
)

/** Supplies and refreshes credentials. Implemented by the data layer. */
interface AuthProvider {
    /** Current lease, or null when logged out. */
    fun currentLease(): AuthLease?

    /**
     * Refreshes and returns a new lease, or null when refresh is impossible
     * (no refresh token, server rejected it). Called under a mutex by [MemohApi].
     */
    suspend fun refresh(previous: AuthLease): AuthLease?

    /** Invoked when the session is definitively gone. */
    fun onSessionExpired()
}

/**
 * REST client for a self-hosted Memoh server.
 *
 * The important behaviour here is credential handling:
 *  - a request carries the freshest lease, refreshing *before* it expires
 *    rather than waiting for a 401, because the server has no refresh token and
 *    an expired token cannot be renewed at all;
 *  - a 401 triggers exactly one refresh-and-retry, serialised through a mutex so
 *    a burst of concurrent 401s causes one refresh rather than N;
 *  - if the retry still fails the session is declared dead and the caller is
 *    told, instead of looping.
 */
class MemohApi(
    private val client: OkHttpClient,
    private val endpoint: ServerEndpoint,
    internal val json: Json,
    private val auth: AuthProvider? = null,
    private val cloudAuth: CloudAuth? = null,
) {
    private val refreshMutex = Mutex()

    /**
     * The bearer token for a WebSocket handshake. Headers are the only safe
     * carrier — a URL would leak the credential into logs.
     */
    fun bearerToken(): String? = auth?.currentLease()?.token

    suspend fun freshBearerToken(): String = freshLease().token

    /** Refresh this long before expiry to stay ahead of clock skew. */
    private val refreshSkewMillis = 60_000L

    // -- unauthenticated ----------------------------------------------------

    suspend fun ping(): PingResponse =
        request<PingResponse>(path = "/ping", authenticated = false)

    suspend fun login(username: String, password: String): LoginResponse =
        post<LoginRequest, LoginResponse>(
            path = "/auth/login",
            body = LoginRequest(username = username, password = password),
            authenticated = false,
        )

    /**
     * Renews the current token. The server has no refresh token, so this only
     * works while the presented JWT is still valid.
     */
    suspend fun refresh(presentedToken: String? = null): RefreshResponse {
        if (presentedToken == null) return post<Unit, RefreshResponse>("/auth/refresh", Unit)
        return send("/auth/refresh", "POST", null, AuthLease(presentedToken, null, 0)).use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiException(it.code, body, "登录续期失败（HTTP ${it.code}）")
            json.decodeFromString(RefreshResponse.serializer(), body)
        }
    }

    // -- authenticated ------------------------------------------------------

    suspend fun me(): CurrentUser = request("/users/me")

    suspend fun bots(): List<Bot> = request<ListResponse<Bot>>("/bots").items

    suspend fun bot(botId: String): Bot = request("/bots/$botId")

    /**
     * The bot's folders (workdirs).
     *
     * Answers `{"workdirs": [...]}` rather than `{"items": [...]}` — the odd one
     * out among the list endpoints. See [WorkdirsResponse].
     */
    suspend fun workdirs(botId: String): List<Workdir> =
        request<WorkdirsResponse>("/bots/$botId/workdirs").workdirs

    suspend fun sessions(
        botId: String,
        types: String? = Session.CHAT_TYPES,
        limit: Int = 50,
        cursor: String? = null,
    ): ListResponse<Session> {
        val query = buildString {
            append("?limit=").append(limit)
            if (types != null) append("&types=").append(types)
            if (cursor != null) append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
        }
        return request("/bots/$botId/sessions$query")
    }

    suspend fun createSession(botId: String, title: String?, workdirId: String? = null): Session {
        val payload = buildMap<String, JsonElement> {
            title?.takeIf(String::isNotBlank)?.let {
                put("title", kotlinx.serialization.json.JsonPrimitive(it))
            }
            workdirId?.let { put("workdir_id", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        return postJson("/bots/$botId/sessions", payload)
    }

    suspend fun renameSession(botId: String, sessionId: String, title: String): Session =
        patchJson(
            "/bots/$botId/sessions/$sessionId",
            mapOf("title" to kotlinx.serialization.json.JsonPrimitive(title)),
        )

    suspend fun deleteSession(botId: String, sessionId: String) {
        delete("/bots/$botId/sessions/$sessionId")
    }

    /**
     * History, newest page first. [beforeMessageId] walks backwards; the server
     * returns whole turns, so a page never splits a turn in half.
     */
    suspend fun messages(
        botId: String,
        sessionId: String,
        limit: Int = 50,
        beforeMessageId: String? = null,
    ): ListResponse<UITurn> {
        val query = buildString {
            append("?session_id=").append(sessionId)
            append("&limit=").append(limit)
            beforeMessageId?.let { append("&before_message_id=").append(it) }
        }
        return request("/bots/$botId/messages$query")
    }

    suspend fun queue(botId: String, sessionId: String): SessionQueueResponse =
        request("/bots/$botId/sessions/$sessionId/queue")

    suspend fun models(): List<ChatModel> = request("/models")

    suspend fun providers(): List<ModelProvider> = request("/providers")

    suspend fun agentModels(botId: String, agentId: String): AgentModelCatalog =
        request("/bots/$botId/agents/$agentId/models")

    suspend fun agents(botId: String): List<BotAgent> =
        request<ListResponse<BotAgent>>("/bots/$botId/agents").items

    suspend fun workspaceTargets(botId: String): WorkspaceTargetsResponse =
        request("/bots/$botId/workspace-targets")

    /** Images/files use the same account headers as REST only on this server. */
    suspend fun media(location: String): ByteArray = withContext(Dispatchers.IO) {
        val maxBytes = 16 * 1024 * 1024L
        if (location.startsWith("data:")) {
            require(location.substringBefore(',').contains(";base64")) { "不支持这种内嵌图片" }
            require(location.length <= maxBytes * 4 / 3 + 512) { "图片超过 16 MB" }
            return@withContext java.util.Base64.getMimeDecoder().decode(location.substringAfter(',')).also {
                require(it.size <= maxBytes) { "图片超过 16 MB" }
            }
        }
        val url = when {
            location.startsWith("https://") || location.startsWith("http://") -> location.toHttpUrl()
            location.startsWith("//") -> ("https:$location").toHttpUrl()
            endpoint.memohPrefix.isNotEmpty() && location.startsWith(endpoint.memohPrefix + "/") ->
                (endpoint.origin + location).toHttpUrl()
            else -> endpoint.memoh(location).toHttpUrl()
        }
        val origin = endpoint.origin.toHttpUrl()
        val sameOrigin = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
        require(url.isHttps || sameOrigin) { "图片地址需要 HTTPS" }
        if (sameOrigin && endpoint.isCloud && cloudAuth?.hasSession != true) throw SessionExpiredException()
        var lease = if (sameOrigin && !endpoint.isCloud) freshLease() else null
        fun sendMedia(): Response {
            val builder = Request.Builder().url(url).header("Accept", "image/*, application/octet-stream")
            if (sameOrigin) {
                if (endpoint.isCloud) cloudAuth?.authorize(builder)
                else lease?.let { builder.header("Authorization", "Bearer ${it.token}") }
            }
            val http = (if (sameOrigin) networkClient.newBuilder() else client.newBuilder()
                .cookieJar(okhttp3.CookieJar.NO_COOKIES))
                .followRedirects(false).followSslRedirects(false).build()
            return http.newCall(builder.build()).execute()
        }
        var response = sendMedia()
        if (sameOrigin && response.code == 401) {
            response.close()
            if (endpoint.isCloud) {
                auth?.onSessionExpired()
                throw SessionExpiredException()
            }
            lease = refreshOnce(lease)
            response = sendMedia()
            if (response.code == 401) {
                response.close()
                auth?.onSessionExpired()
                throw SessionExpiredException()
            }
        }
        response.use {
            if (!it.isSuccessful) throw ApiException(it.code, null, "文件加载失败（HTTP ${it.code}）")
            val body = it.body ?: error("文件内容为空")
            require(body.contentLength() <= maxBytes) { "文件超过 16 MB" }
            val source = body.source()
            source.request(maxBytes + 1)
            require(source.buffer.size <= maxBytes) { "文件超过 16 MB" }
            source.readByteArray()
        }
    }

    // -- plumbing -----------------------------------------------------------

    /** Multipart uploads share the same refresh and account headers as JSON. */
    internal suspend fun multipart(path: String, body: okhttp3.RequestBody): JsonElement = withContext(Dispatchers.IO) {
        if (endpoint.isCloud && cloudAuth?.hasSession != true) throw SessionExpiredException()
        var lease = if (!endpoint.isCloud) freshLease() else null
        fun upload() = networkClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request(path, "POST", null, lease).newBuilder().post(body).build()).execute()
        var response = upload()
        if (response.code == 401) {
            response.close()
            if (endpoint.isCloud) { auth?.onSessionExpired(); throw SessionExpiredException() }
            lease = refreshOnce(lease)
            response = upload()
            if (response.code == 401) { response.close(); auth?.onSessionExpired(); throw SessionExpiredException() }
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiException(it.code, text, "上传失败（HTTP ${it.code}）")
            json.parseToJsonElement(text)
        }
    }

    internal suspend fun binaryPost(path: String, body: okhttp3.RequestBody, limitBytes: Long = 16 * 1024 * 1024L): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        if (endpoint.isCloud && cloudAuth?.hasSession != true) throw SessionExpiredException()
        var lease = if (!endpoint.isCloud) freshLease() else null
        fun sendBody() = networkClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request(path, "POST", null, lease).newBuilder().post(body).build()).execute()
        var response = sendBody()
        if (response.code == 401) {
            response.close()
            if (endpoint.isCloud) { auth?.onSessionExpired(); throw SessionExpiredException() }
            lease = refreshOnce(lease); response = sendBody()
            if (response.code == 401) { response.close(); auth?.onSessionExpired(); throw SessionExpiredException() }
        }
        response.use {
            if (!it.isSuccessful) throw ApiException(it.code, it.body.string(), "请求失败（HTTP ${it.code}）")
            val source = it.body.source()
            source.request(limitBytes + 1)
            require(source.buffer.size <= limitBytes) { "返回的文件超过 ${limitBytes / 1024 / 1024} MB" }
            it.body.contentType()?.toString().orEmpty().substringBefore(';').ifBlank { "audio/mpeg" } to source.readByteArray()
        }
    }

    private suspend inline fun <reified T> request(
        path: String,
        authenticated: Boolean = true,
    ): T = execute(path, "GET", null, authenticated, serializer<T>())

    private suspend inline fun <reified B, reified T> post(
        path: String,
        body: B,
        authenticated: Boolean = true,
    ): T = execute(
        path = path,
        method = "POST",
        body = json.encodeToString(serializer<B>(), body),
        authenticated = authenticated,
        deserializer = serializer<T>(),
    )

    private suspend inline fun <reified T> postJson(
        path: String,
        body: Map<String, JsonElement>,
    ): T = execute(
        path = path,
        method = "POST",
        body = json.encodeToString(MapSerializer, body),
        authenticated = true,
        deserializer = serializer<T>(),
    )

    private suspend inline fun <reified T> patchJson(
        path: String,
        body: Map<String, JsonElement>,
    ): T = execute(
        path = path,
        method = "PATCH",
        body = json.encodeToString(MapSerializer, body),
        authenticated = true,
        deserializer = serializer<T>(),
    )

    private suspend fun delete(path: String) {
        executeUnit(path, "DELETE", null)
    }

    private suspend inline fun <reified T> execute(
        path: String,
        method: String,
        body: String?,
        authenticated: Boolean,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T = call(path, method, body, authenticated, deserializer)

    private suspend fun executeUnit(path: String, method: String, body: String?) {
        call(path, method, body, authenticated = true, deserializer = UnitSerializer)
    }

    /**
     * Sends a request, refreshing credentials once if the server rejects them.
     *
     * @param deserializer how to read a successful body; pass [UnitSerializer]
     *        for endpoints that return nothing.
     */
    internal suspend fun <T> call(
        path: String,
        method: String,
        body: String?,
        authenticated: Boolean = true,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        if (authenticated && endpoint.isCloud && cloudAuth?.hasSession != true) throw SessionExpiredException()
        var lease = if (authenticated && !endpoint.isCloud) freshLease() else null
        var response = send(path, method, body, lease)

        if (authenticated && response.code == 401) {
            response.close()
            if (endpoint.isCloud) {
                auth?.onSessionExpired()
                throw SessionExpiredException()
            }
            // One refresh, then one retry. A second 401 means the credentials
            // are genuinely gone, not merely stale.
            lease = refreshOnce(lease)
            response = send(path, method, body, lease)
            if (response.code == 401) {
                response.close()
                auth?.onSessionExpired()
                throw SessionExpiredException()
            }
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw ApiException(it.code, text, "$method $path failed: ${it.code}")
            }
            @Suppress("UNCHECKED_CAST")
            if (deserializer == UnitSerializer) Unit as T else json.decodeFromString(deserializer, text)
        }
    }

    /** Returns a lease that is valid now, refreshing pre-emptively if needed. */
    private suspend fun freshLease(): AuthLease {
        val provider = auth ?: throw SessionExpiredException()
        val current = provider.currentLease() ?: throw SessionExpiredException()
        val expires = current.expiresAtMillis
        if (expires == null || expires - System.currentTimeMillis() > refreshSkewMillis) {
            return current
        }
        return refreshOnce(current)
    }

    /**
     * Serialises refresh: concurrent callers queue behind one refresh and then
     * reuse its result, rather than each firing their own.
     */
    private suspend fun refreshOnce(previous: AuthLease?): AuthLease {
        val provider = auth ?: throw SessionExpiredException()
        return refreshMutex.withLock {
            // Another coroutine may have refreshed while we waited.
            val latest = provider.currentLease()
            if (previous != null && latest != null && latest.token != previous.token) {
                return@withLock latest
            }
            val base = latest ?: previous ?: throw SessionExpiredException()
            provider.refresh(base) ?: run {
                provider.onSessionExpired()
                throw SessionExpiredException()
            }
        }
    }

    /**
     * Performs the blocking HTTP exchange.
     *
     * Runs on [Dispatchers.IO]: OkHttp's synchronous `execute()` refuses to run
     * on the main thread, and every caller here is a suspend function that may
     * have been launched from a ViewModel scope (which defaults to Main).
     */
    private suspend fun send(
        path: String,
        method: String,
        body: String?,
        lease: AuthLease?,
    ): Response = withContext(Dispatchers.IO) {
        networkClient.newCall(request(path, method, body, lease)).execute()
    }

    internal val networkClient: OkHttpClient get() = if (endpoint.isCloud) cloudAuth?.http ?: client else client

    internal suspend fun socketRequest(path: String): Request {
        if (endpoint.isCloud) {
            val cloud = cloudAuth?.takeIf { it.hasSession } ?: throw SessionExpiredException()
            try { return cloud.authorize(Request.Builder().url(cloud.ticketedSocketUrl(path))).build() }
            catch (e: ApiException) {
                if (e.status == 401) { invalidateSession(); throw SessionExpiredException() }
                throw e
            }
        }
        return Request.Builder().url(endpoint.memoh(path)).header("Authorization", "Bearer ${freshLease().token}").build()
    }

    internal val workspaceCandidateHost: String get() = endpoint.origin.toHttpUrl().host
    internal val runtimeDisplay: Boolean get() = endpoint.isCloud
    internal suspend fun runtimeDisplayRequest(session: dev.memoh.core.model.RuntimeDisplaySession): Request {
        val cloud = cloudAuth?.takeIf { it.hasSession } ?: throw SessionExpiredException()
        return cloud.runtimeDisplayRequest(session)
    }

    internal suspend fun streamRequest(path: String, body: String? = null, method: String = if (body == null) "GET" else "POST"): Request {
        if (endpoint.isCloud && cloudAuth?.hasSession != true) throw SessionExpiredException()
        val lease = if (!endpoint.isCloud) freshLease() else null
        return request(path, method, body, lease).newBuilder()
            .header("Accept", "text/event-stream").build()
    }

    internal fun streamUnauthorized() { auth?.onSessionExpired() }

    fun invalidateSession() { auth?.onSessionExpired() }

    private fun request(path: String, method: String, body: String?, lease: AuthLease?): Request {
        val builder = Request.Builder().url(endpoint.memoh(path)).header("Accept", "application/json")
        if (endpoint.isCloud) cloudAuth?.authorize(builder)
        else lease?.let { builder.header("Authorization", "Bearer ${it.token}") }

        val emptyBody = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: emptyBody)
            "PATCH" -> builder.patch(requestBody ?: emptyBody)
            "PUT" -> builder.put(requestBody ?: emptyBody)
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> throw IllegalArgumentException("unsupported method $method")
        }
        return builder.build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Endpoints that answer 204 or an empty body. */
        val UnitSerializer: KSerializer<Unit> = Unit.serializer()

        /** Bodies built as loose JSON objects rather than typed payloads. */
        val MapSerializer: KSerializer<Map<String, JsonElement>> =
            MapSerializer(String.serializer(), JsonElement.serializer())
    }
}
