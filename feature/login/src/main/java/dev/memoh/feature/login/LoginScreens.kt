package dev.memoh.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohActionButton

/**
 * Server selection: the official cloud or a self-hosted instance.
 *
 * Presented as a single choice rather than a hidden default, because the two
 * have different auth flows and a user who picks wrong cannot log in at all.
 */
@Composable
fun ServerPickerScreen(
    onSelectCloud: () -> Unit,
    onSelectSelfHosted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        DotMatrixBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MemohWordmark()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "连接到你的 Memoh 服务器",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))

            Column(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MemohActionButton("使用 Memoh Cloud", Icons.Filled.Cloud, onSelectCloud,
                    modifier = Modifier.fillMaxWidth().height(52.dp), primary = true)
                MemohActionButton("自托管服务器", Icons.Filled.Dns, onSelectSelfHosted,
                    modifier = Modifier.fillMaxWidth().height(52.dp))
            }
        }
    }
}

/**
 * Self-hosted sign-in.
 *
 * The URL is verified against `/ping` before credentials are sent, so a typo
 * surfaces as "cannot reach server" rather than a confusing auth error.
 */
@Composable
fun SelfHostedLoginScreen(
    state: LoginUiState,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
                MemohActionButton("返回", Icons.AutoMirrored.Filled.ArrowBack, onBack, modifier = Modifier.padding(bottom = 8.dp))
                Text(
                    text = "登录自托管服务器",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "地址需支持 HTTPS，并运行 Memoh 服务端",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChange,
                    label = { Text("服务器地址") },
                    placeholder = { Text("memoh.example.com") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text("用户名或邮箱") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.serverInfo != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "已连接 · 版本 ${state.serverInfo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                state.error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(24.dp))
                LoginSubmitButton(
                    label = "登录",
                    busy = state.busy,
                    enabled = state.canSubmit,
                    onClick = onSubmit,
                )
            }
        }
    }
}

/**
 * Official cloud sign-in: email, then a code.
 *
 * The code step is a separate state rather than a second field on the same form
 * because the resend cooldown and the MFA branch only make sense once the email
 * has been accepted.
 */
@Composable
fun CloudLoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        DotMatrixBackground(animate = state.codeSentAt == null)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
                MemohActionButton("返回", Icons.AutoMirrored.Filled.ArrowBack, onBack, modifier = Modifier.padding(bottom = 8.dp))
                MemohWordmark()
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = { Text("邮箱") },
                    singleLine = true,
                    enabled = !state.busy && state.mfaToken == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSendCode() }),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.codeSentAt != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCodeChange,
                        label = { Text(if (state.mfaToken != null) "身份验证器中的验证码" else "验证码") },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onVerify() }),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.mfaToken == null && state.resendInSeconds > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${state.resendInSeconds} 秒后可重新发送",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(24.dp))
                if (state.codeSentAt == null) {
                    LoginSubmitButton(
                        label = "发送验证码",
                        busy = state.busy,
                        enabled = state.canSubmitEmail,
                        onClick = onSendCode,
                    )
                } else {
                    LoginSubmitButton(
                        label = if (state.mfaToken != null) "完成两步验证" else "验证并登录",
                        busy = state.busy,
                        enabled = state.canSubmitCode,
                        onClick = onVerify,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.mfaToken == null) MemohActionButton("重新发送验证码", Icons.Filled.Refresh,
                        onClick = onSendCode,
                        enabled = state.resendInSeconds == 0 && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The one primary action per screen. It swaps its label for an indicator while
 * busy so the button never changes size and the layout stays still.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoginSubmitButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(),
    ) {
        if (busy) {
            LoadingIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Workspace selection for cloud accounts that belong to several teams.
 *
 * Shown only when there is a genuine choice: a single-team account goes
 * straight through, because a picker with one option is friction.
 */
@Composable
fun TeamPickerScreen(
    teams: List<Pair<String, String>>,
    busy: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        DotMatrixBackground(animate = false)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
                Text(
                    text = "选择工作区",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "会话与 Bot 按工作区隔离",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    teams.forEach { (id, name) ->
                        MemohActionButton(name, Icons.Filled.Groups,
                            onClick = { onSelect(id) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        )
                    }
                }
            }
        }
    }
}
