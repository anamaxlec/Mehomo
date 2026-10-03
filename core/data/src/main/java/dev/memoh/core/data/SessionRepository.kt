package dev.memoh.core.data

import dev.memoh.core.model.Bot
import dev.memoh.core.model.Session
import dev.memoh.core.network.AuthLease
import dev.memoh.core.network.AuthProvider
import dev.memoh.core.network.MemohApi
import dev.memoh.core.network.ServerEndpoint
import dev.memoh.core.network.CloudAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val generation: Long get() = credentials.generation.value

    init {
        credentials.activeAccount()?.let { account ->
            if (account.kind == "cloud") cloudAuth.teamId = account.teamId
            _state.value = SessionState(
                loggedIn = if (account.kind == "cloud") cloudAuth.hasSession else credentials.token(account.accountId) != null,
                account = account, endpoint = endpointFor(account),
            )
        }
    }

    // -- AuthProvider -------------------------------------------------------

    override fun currentLease(): AuthLease? = credentials.lease()

    override suspend fun refresh(previous: AuthLease): AuthLease? {
        val account = credentials.activeAccount() ?: return null
        val endpoint = endpointFor(account)
        val api = MemohApi(
            client = httpClient,
            endpoint = endpoint,
            json = json,
            auth = null,
        )
        return try {
            val refreshed = api.refresh(previous.token)
            credentials.saveToken(account.accountId, refreshed.accessToken, parseExpiry(refreshed.expiresAt))
            AuthLease(
                token = refreshed.accessToken,
                expiresAtMillis = parseExpiry(refreshed.expiresAt),
                generation = credentials.generation.value,
            )
        } catch (e: Exception) {
            // A failed refresh is terminal for this account: there is no refresh
            // token to fall back on.
            null
        }
    }

    override fun onSessionExpired() {
        val accountId = credentials.activeAccountId() ?: return
        if (credentials.activeAccount()?.kind == "cloud") cloudAuth.clearSession()
        credentials.clearToken(accountId)
        _state.value = _state.value.copy(loggedIn = false, sessionExpired = true)
    }

    // -- account switching --------------------------------------------------

    /** Points the repository at an account and rebuilds its API surface. */
    fun activate(account: StoredAccount) {
        credentials.setActiveAccount(account.accountId)
        if (account.kind == "cloud") cloudAuth.teamId = account.teamId
        _state.value = SessionState(
            loggedIn = if (account.kind == "cloud") cloudAuth.hasSession else credentials.token(account.accountId) != null,
            account = account,
            endpoint = endpointFor(account),
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

    fun signOut() {
        if (credentials.activeAccount()?.kind == "cloud") cloudAuth.clearSession()
        credentials.activeAccountId()?.let { credentials.clearToken(it) }
        _state.value = SessionState()
    }

    /** Removes an account entirely, including its stored token. */
    fun removeAccount(accountId: String) {
        credentials.removeAccount(accountId)
        if (credentials.activeAccountId() == null) {
            _state.value = SessionState()
        }
    }

    fun saveToken(accountId: String, token: String, expiresAt: String?) {
        credentials.saveToken(accountId, token, parseExpiry(expiresAt))
    }

    /** Builds an API client for the active account, or null when signed out. */
    fun api(): MemohApi? {
        val account = credentials.activeAccount() ?: return null
        return MemohApi(
            client = httpClient,
            endpoint = endpointFor(account),
            json = json,
            auth = this,
            cloudAuth = cloudAuth,
        )
    }

    /** Builds an API client for an arbitrary account (used before activation). */
    fun apiFor(account: StoredAccount): MemohApi = MemohApi(
        client = httpClient,
        endpoint = endpointFor(account),
        json = json,
        auth = this,
        cloudAuth = cloudAuth,
    )

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
)

/** Convenience: the bot list for the active account. */
suspend fun SessionRepository.bots(): List<Bot> =
    api()?.bots() ?: emptyList()

/** Convenience: chat sessions for a bot. */
suspend fun SessionRepository.sessions(botId: String): List<Session> =
    api()?.sessions(botId)?.items ?: emptyList()
