package dev.memoh.core.data

import dev.memoh.core.model.Bot
import dev.memoh.core.model.Session
import dev.memoh.core.network.AuthLease
import dev.memoh.core.network.AuthProvider
import dev.memoh.core.network.ApiException
import dev.memoh.core.network.MemohApi
import dev.memoh.core.network.ServerEndpoint
import dev.memoh.core.network.CloudAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Owns the session's identity: which server, which account, which bot.
 *
 * Every network object is rebuilt when the account changes, and the generation
 * counter is the guard that keeps a response from a previous account from
 * landing in the new one's state.
 */
class SessionRepository(
    private val credentials: CredentialStore,
    private val json: Json,
    private val cloudAuth: CloudAuth,
) : AuthProvider {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Bumped whenever the account changes; callers snapshot it before a request. */
    override val generation: Long get() = credentials.generation.value

    init {
        credentials.activeAccount()?.let { account ->
            if (account.kind == "cloud") cloudAuth.teamId = account.teamId
            _state.value = SessionState(
                loggedIn = if (account.kind == "cloud") cloudAuth.hasSession else credentials.token(account.accountId) != null,
                account = account, endpoint = endpointFor(account),
                generation = generation,
            )
        }
    }

    // -- AuthProvider -------------------------------------------------------

    override fun currentLease(): AuthLease? = synchronized(credentials) {
        credentials.lease().takeIf { state.value.generation == generation }
    }

    override suspend fun refresh(previous: AuthLease): AuthLease? {
        val account = synchronized(credentials) {
            requireGeneration(previous.generation)
            val current = credentials.lease() ?: return null
            if (current.token != previous.token) return current
            credentials.activeAccount() ?: return null
        }
        val endpoint = endpointFor(account)
        val api = MemohApi(
            client = httpClient,
            endpoint = endpoint,
            json = json,
            auth = null,
        )
        return try {
            val refreshed = api.refresh(previous.token)
            synchronized(credentials) {
                requireGeneration(previous.generation)
                val current = credentials.lease() ?: return null
                if (current.token != previous.token) return current
                credentials.saveToken(account.accountId, refreshed.accessToken, parseExpiry(refreshed.expiresAt))
                credentials.lease()
            }
        } catch (e: ApiException) {
            requireGeneration(previous.generation)
            // A transport failure is retryable; only rejected credentials expire the login.
            if (e.isUnauthorized) null else throw e
        }
    }

    private fun requireGeneration(expected: Long) {
        if (generation != expected) throw CancellationException("Account changed during token refresh")
    }

    override fun onSessionExpired(generation: Long) = synchronized(credentials) {
        if (this.generation != generation) return
        val accountId = credentials.activeAccountId() ?: return
        if (credentials.activeAccount()?.kind == "cloud") cloudAuth.clearSession()
        credentials.clearToken(accountId)
        _state.value = _state.value.copy(loggedIn = false, sessionExpired = true, generation = this.generation)
    }

    // -- account switching --------------------------------------------------

    /** Points the repository at an account and rebuilds its API surface. */
    fun activate(account: StoredAccount) = synchronized(credentials) {
        credentials.setActiveAccount(account.accountId)
        if (account.kind == "cloud") cloudAuth.teamId = account.teamId
        _state.value = SessionState(
            loggedIn = if (account.kind == "cloud") cloudAuth.hasSession else credentials.token(account.accountId) != null,
            account = account,
            endpoint = endpointFor(account),
            generation = generation,
        )
    }

    suspend fun cloudTeams(): List<dev.memoh.core.model.TeamMembership> {
        check(state.value.account?.kind == "cloud" && state.value.loggedIn) { "请先登录 Memoh Cloud" }
        return cloudAuth.teams()
    }

    fun selectCloudTeam(team: dev.memoh.core.model.Team) {
        val current = state.value.account ?: return
        check(current.kind == "cloud" && state.value.loggedIn) { "请先登录 Memoh Cloud" }
        if (current.teamId == team.teamId) return
        val account = current.copy(accountId = "cloud:${current.username.orEmpty()}:${team.teamId}", teamId = team.teamId)
        credentials.saveAccount(account)
        activate(account)
    }

    suspend fun cloudRead(path: String, unscoped: Boolean = false) = cloudAuth.platformRead(path, unscoped)
    suspend fun cloudWrite(path: String, method: String, body: kotlinx.serialization.json.JsonObject? = null, unscoped: Boolean = false) = cloudAuth.platformWrite(path, method, body, unscoped)

    suspend fun reloadAfterCloudTeamRemoved() {
        val current = state.value.account ?: return
        val unscoped = current.copy(accountId = "cloud:${current.username.orEmpty()}:", teamId = null)
        credentials.saveAccount(unscoped)
        activate(unscoped)
        cloudTeams().firstNotNullOfOrNull { it.team }?.let(::selectCloudTeam)
    }

    fun signOut() = synchronized(credentials) {
        if (credentials.activeAccount()?.kind == "cloud") cloudAuth.clearSession()
        credentials.activeAccountId()?.let { credentials.clearToken(it) }
        _state.value = SessionState(generation = generation)
    }

    /** Removes an account entirely, including its stored token. */
    fun removeAccount(accountId: String) = synchronized(credentials) {
        credentials.removeAccount(accountId)
        if (credentials.activeAccountId() == null) {
            _state.value = SessionState(generation = generation)
        }
    }

    fun saveToken(accountId: String, token: String, expiresAt: String?) {
        credentials.saveToken(accountId, token, parseExpiry(expiresAt))
    }

    /** Builds an API client for the active account, or null when signed out. */
    fun api(): MemohApi? = synchronized(credentials) {
        if (!state.value.loggedIn || state.value.generation != generation) return null
        val account = credentials.activeAccount() ?: return null
        MemohApi(
            client = httpClient,
            endpoint = endpointFor(account),
            json = json,
            auth = this,
            cloudAuth = cloudAuth,
        )
    }

    /** A client must never pair another account's endpoint with the active credentials. */
    fun apiFor(account: StoredAccount): MemohApi = synchronized(credentials) {
        require(account.accountId == credentials.activeAccountId()) { "请先切换到这个账号" }
        api() ?: throw dev.memoh.core.network.SessionExpiredException()
    }

    fun endpointFor(account: StoredAccount): ServerEndpoint =
        if (account.kind == "cloud") {
            ServerEndpoint.OfficialCloud
        } else {
            ServerEndpoint(
                kind = dev.memoh.core.network.ServerKind.SelfHosted,
                origin = account.origin,
                apiPrefix = account.apiPrefix,
                memohPrefix = account.apiPrefix,
            )
        }

    /**
     * Parses an ISO-8601 expiry into epoch millis. Returns null when absent or
     * unparseable, which the API layer treats as "no known expiry".
     */
    private fun parseExpiry(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * The shared HTTP client. Redirects are disabled so a redirect can never
     * carry the bearer token to another host.
     */
    private val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** Identity of the current session. */
data class SessionState(
    val loggedIn: Boolean = false,
    val account: StoredAccount? = null,
    val endpoint: ServerEndpoint? = null,
    /** True when the server rejected our credentials and the user must re-auth. */
    val sessionExpired: Boolean = false,
    /** Identity snapshot so re-authenticating the same account also rebuilds its clients. */
    val generation: Long = 0L,
)

/** Convenience: the bot list for the active account. */
suspend fun SessionRepository.bots(): List<Bot> =
    api()?.bots() ?: emptyList()

/** Convenience: chat sessions for a bot. */
suspend fun SessionRepository.sessions(botId: String): List<Session> =
    api()?.sessions(botId)?.items ?: emptyList()
