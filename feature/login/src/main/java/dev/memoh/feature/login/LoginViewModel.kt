package dev.memoh.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.StoredAccount
import dev.memoh.core.network.ApiException
import dev.memoh.core.network.CloudAuth
import dev.memoh.core.network.MemohApi
import dev.memoh.core.network.ServerKind
import dev.memoh.core.network.ServerUrl
import dev.memoh.core.network.UrlParseResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Which login surface is showing. */
enum class LoginStep { ServerPicker, SelfHosted, Cloud, TeamPicker }

data class LoginFlowState(
    val step: LoginStep = LoginStep.ServerPicker,
    val form: LoginUiState = LoginUiState(),
    val teams: List<Pair<String, String>> = emptyList(),
)

/**
 * Drives both auth flows.
 *
 * Self-hosted and cloud differ enough that they share only the form state:
 * self-hosted exchanges a password for a JWT we can store, while the cloud
 * keeps an HttpOnly cookie the client cannot read, so its "session" lives only
 * as long as the process.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val credentials: CredentialStore,
    private val session: SessionRepository,
    private val json: Json,
    private val httpClient: okhttp3.OkHttpClient,
    private val cloudAuth: CloudAuth,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginFlowState())
    val state: StateFlow<LoginFlowState> = _state.asStateFlow()

    /** Set once a login completes, so the shell can navigate away. */
    private val _signedIn = MutableStateFlow(session.state.value.loggedIn)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private var resendJob: Job? = null

    // -- navigation ---------------------------------------------------------

    fun showSelfHosted() = _state.update { it.copy(step = LoginStep.SelfHosted, form = it.form.copy(error = null)) }

    fun showCloud() = _state.update { it.copy(step = LoginStep.Cloud, form = it.form.copy(error = null)) }

    fun backToPicker() = _state.update { it.copy(step = LoginStep.ServerPicker, form = it.form.copy(error = null)) }

    // -- self-hosted --------------------------------------------------------

    fun onUrlChange(value: String) = _state.update { it.copy(form = it.form.copy(url = value, error = null, serverInfo = null)) }

    fun onUsernameChange(value: String) = _state.update { it.copy(form = it.form.copy(username = value, error = null)) }

    fun onPasswordChange(value: String) = _state.update { it.copy(form = it.form.copy(password = value, error = null)) }

    /**
     * Verifies the server, then logs in.
     *
     * Reachability is checked first so the user learns "wrong address" rather
     * than "wrong password" when the URL is at fault. The `/api` suffix is
     * probed because the official nginx image mounts the backend there.
     */
    fun submitSelfHosted() {
        val form = _state.value.form
        if (!form.canSubmit) return
        _state.update { it.copy(form = it.form.copy(busy = true, error = null)) }

        viewModelScope.launch {
            val candidates = ServerUrl.candidates(form.url)
            if (candidates.isEmpty()) {
                fail("地址无效，请输入完整域名")
                return@launch
            }

            var reachable: dev.memoh.core.network.ServerEndpoint? = null
            var ping: dev.memoh.core.model.PingResponse? = null
            for (candidate in candidates) {
                val probe = MemohApi(httpClient, candidate, json, auth = null)
                val result = runCatching { probe.ping() }
                val info = result.getOrNull()
                if (info != null && info.isOk) {
                    reachable = candidate
                    ping = info
                    break
                }
            }

            val endpoint = reachable
            if (endpoint == null) {
                fail("无法连接到该服务器，请检查地址与网络")
                return@launch
            }

            val api = MemohApi(httpClient, endpoint, json, auth = null)
            val login = runCatching { api.login(form.username.trim(), form.password) }
            login.fold(
                onSuccess = { response ->
                    val account = StoredAccount(
                        accountId = "${endpoint.origin}:${response.userId ?: form.username.trim()}",
                        origin = endpoint.origin,
                        kind = "selfhosted",
                        apiPrefix = endpoint.apiPrefix,
                        userId = response.userId,
                        username = response.username ?: form.username.trim(),
                        displayName = response.displayName,
                    )
                    credentials.saveAccount(account)
                    credentials.saveToken(account.accountId, response.accessToken, null)
                    session.activate(account)
                    _state.update {
                        it.copy(form = it.form.copy(busy = false, serverInfo = ping?.version))
                    }
                    _signedIn.value = true
                },
                onFailure = { error ->
                    fail(
                        if (error is ApiException && error.status == 401) {
                            "用户名或密码错误"
                        } else {
                            describeFailure("登录失败", error)
                        },
                    )
                },
            )
        }
    }

    // -- official cloud -----------------------------------------------------

    fun onEmailChange(value: String) = _state.update { it.copy(form = it.form.copy(email = value, error = null)) }

    fun onCodeChange(value: String) = _state.update { it.copy(form = it.form.copy(code = value, error = null)) }

    fun sendEmailCode() {
        val form = _state.value.form
        if (!form.canSubmitEmail) return
        _state.update { it.copy(form = it.form.copy(busy = true, error = null)) }

        viewModelScope.launch {
            val auth = cloudAuth
            runCatching { auth.sendEmailCode(form.email.trim()) }.fold(
                onSuccess = { response ->
                    _state.update {
                        it.copy(
                            form = it.form.copy(
                                busy = false,
                                codeSentAt = System.currentTimeMillis(),
                                resendInSeconds = response.resendAfter ?: DEFAULT_RESEND_SECONDS,
                            ),
                        )
                    }
                    startResendCountdown()
                },
                onFailure = { error -> fail(describeFailure("发送验证码失败", error)) },
            )
        }
    }

    fun verifyEmailCode() {
        val form = _state.value.form
        if (!form.canSubmitCode) return
        _state.update { it.copy(form = it.form.copy(busy = true, error = null)) }

        viewModelScope.launch {
            val auth = cloudAuth
            val verify = runCatching { auth.verifyEmailCode(form.email.trim(), form.code.trim()) }
            val response = verify.getOrElse { error ->
                fail(describeFailure("验证失败", error))
                return@launch
            }

            if (response.mfaRequired == true) {
                _state.update { it.copy(form = it.form.copy(busy = false, mfaToken = response.mfaToken, code = "")) }
                return@launch
            }

            // A single team can be entered directly; several must be chosen,
            // because the session is scoped to one workspace at a time.
            val teams = runCatching { auth.teams() }.getOrElse {
                fail(describeFailure("读取工作空间失败", it)); return@launch
            }
            when {
                teams.isEmpty() -> completeCloudLogin(auth, form.email.trim(), teamId = null)
                teams.size == 1 -> {
                    val only = teams.first()
                    auth.teamId = only.team?.teamId
                    completeCloudLogin(auth, form.email.trim(), teamId = only.team?.teamId)
                }
                else -> {
                    _state.update {
                        it.copy(
                            step = LoginStep.TeamPicker,
                            form = it.form.copy(busy = false),
                            teams = teams.mapNotNull { membership ->
                                val team = membership.team ?: return@mapNotNull null
                                team.teamId to (team.name.ifBlank { team.slug ?: team.teamId })
                            },
                        )
                    }
                }
            }
        }
    }

    fun selectTeam(teamId: String) {
        val form = _state.value.form
        _state.update { it.copy(form = it.form.copy(busy = true)) }
        viewModelScope.launch {
            val auth = cloudAuth
            auth.teamId = teamId
            completeCloudLogin(auth, form.email.trim(), teamId)
        }
    }

    private fun completeCloudLogin(auth: CloudAuth, email: String, teamId: String?) {
        if (!auth.hasSession) { fail("服务器未返回有效的登录会话，请重新登录"); return }
        val account = StoredAccount(
            accountId = "cloud:$email:${teamId.orEmpty()}",
            origin = dev.memoh.core.network.ServerEndpoint.CLOUD_ORIGIN,
            kind = "cloud",
            apiPrefix = dev.memoh.core.network.ServerEndpoint.CLOUD_API_PREFIX,
            username = email,
            displayName = email.substringBefore('@'),
            teamId = teamId,
        )
        credentials.saveAccount(account)
        session.activate(account)
        _state.update { it.copy(form = it.form.copy(busy = false)) }
        _signedIn.value = true
    }

    fun verifyMfa() {
        val form = _state.value.form
        val token = form.mfaToken ?: return
        if (!form.canSubmitCode) return
        _state.update { it.copy(form = it.form.copy(busy = true, error = null)) }
        viewModelScope.launch {
            runCatching {
                cloudAuth.verifyMfa(token, form.code.trim())
                cloudAuth.teams()
            }.fold(onSuccess = { teams ->
                if (teams.size == 1) {
                    cloudAuth.teamId = teams.first().team?.teamId
                    completeCloudLogin(cloudAuth, form.email.trim(), cloudAuth.teamId)
                } else if (teams.isEmpty()) {
                    completeCloudLogin(cloudAuth, form.email.trim(), null)
                } else {
                    _state.update { it.copy(step = LoginStep.TeamPicker, teams = teams.mapNotNull { item ->
                        item.team?.let { team -> team.teamId to team.name.ifBlank { team.teamId } }
                    }, form = it.form.copy(busy = false, mfaToken = null)) }
                }
            }, onFailure = { fail(describeFailure("两步验证失败", it)) })
        }
    }

    private fun startResendCountdown() {
        resendJob?.cancel()
        resendJob = viewModelScope.launch {
            while (_state.value.form.resendInSeconds > 0) {
                delay(1_000)
                _state.update {
                    it.copy(form = it.form.copy(resendInSeconds = (it.form.resendInSeconds - 1).coerceAtLeast(0)))
                }
            }
        }
    }

    /**
     * Builds a user-facing failure line.
     *
     * The exception's own message is often null (many IO exceptions carry none),
     * so the type name is included as a fallback: "检查邮箱地址" would be a lie
     * when the real cause is an unreachable network.
     */
    private fun describeFailure(prefix: String, error: Throwable): String {
        val detail = error.message?.takeIf(String::isNotBlank)
            ?: error::class.java.simpleName
        val status = (error as? ApiException)?.status
        return if (status != null) "$prefix（HTTP $status）：$detail" else "$prefix：$detail"
    }

    private fun fail(message: String) {
        _state.update { it.copy(form = it.form.copy(busy = false, error = message)) }
    }

    override fun onCleared() {
        super.onCleared()
        resendJob?.cancel()
    }

    private companion object {
        const val DEFAULT_RESEND_SECONDS = 60
    }
}

/** True when the stored account is the official cloud (cookie session). */
fun StoredAccount.isCloud(): Boolean = kind == "cloud" || origin.contains("memoh.net")
