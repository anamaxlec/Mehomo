package dev.memoh.feature.login

/**
 * Login form state.
 *
 * [canSubmit] variants live here rather than in the composable so the rules are
 * testable and identical for both auth paths.
 */
data class LoginUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val email: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** Server version once `/ping` succeeds, shown as a connection confirmation. */
    val serverInfo: String? = null,
    /** Non-null once a code has been requested; switches the UI to the code step. */
    val codeSentAt: Long? = null,
    val resendInSeconds: Int = 0,
    /** Set when the account has MFA enabled and a TOTP code is required. */
    val mfaToken: String? = null,
) {
    val canSubmit: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !busy

    val canSubmitEmail: Boolean
        get() = email.contains('@') && !busy

    val canSubmitCode: Boolean
        get() = code.isNotBlank() && !busy
}
