package app.strategyforge.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.strategyforge.android.core.state.AccessState
import app.strategyforge.android.core.state.AccessStep
import app.strategyforge.android.core.state.ActionState
import java.time.ZoneId

/** Callbacks of the access flow; the composable itself is stateless apart from form fields. */
data class AccessActions(
    val connect: (String) -> Unit,
    val bootstrap: (username: String, password: String, confirm: String, token: String?) -> Unit,
    val login: (username: String, password: String, totp: String?) -> Unit,
    val recover: (username: String, code: String, newPassword: String) -> Unit,
    val showRecovery: () -> Unit,
    val showLogin: () -> Unit,
    val codesSaved: () -> Unit,
    val changeServer: () -> Unit,
)

@Composable
fun AccessScreen(
    state: AccessState,
    actions: AccessActions,
) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("StrategyForge", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Banner("Private paper-trading app. All trading is simulated; no real money or brokerage account is used.")
        when (state.step) {
            AccessStep.SERVER -> ServerForm(state, actions)
            AccessStep.BOOTSTRAP -> BootstrapForm(state, actions)
            AccessStep.LOGIN -> LoginForm(state, actions)
            AccessStep.RECOVERY -> RecoveryForm(actions)
            AccessStep.RECOVERY_CODES -> RecoveryCodes(state, actions)
            AccessStep.SIGNED_IN -> Loading()
        }
        ActionFeedback(state.action)
    }
}

@Composable
private fun ServerForm(
    state: AccessState,
    actions: AccessActions,
) {
    var url by rememberSaveable { mutableStateOf(state.serverUrl.ifBlank { "https://" }) }
    SectionTitle("Connect to your backend")
    Text("Enter the HTTPS address of your StrategyForge backend (for example over your private VPN).")
    Field("Backend address", url, { url = it }, modifier = Modifier.testTag("server"))
    Button(onClick = { actions.connect(url) }, enabled = state.action != ActionState.Running) { Text("Connect") }
}

@Composable
private fun BootstrapForm(
    state: AccessState,
    actions: AccessActions,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    SectionTitle("Create the owner account")
    Text("This backend has no owner yet. The account you create here is the only account.")
    Field("Username", user, { user = it })
    Field("Password (12+ characters)", pass, { pass = it }, password = true)
    Field("Confirm password", confirm, { confirm = it }, password = true)
    if (state.bootstrapTokenRequired) Field("Bootstrap token", token, { token = it }, password = true)
    Button(onClick = { actions.bootstrap(user, pass, confirm, token.ifBlank { null }) }, enabled = state.action != ActionState.Running) { Text("Create owner") }
    TextButton(onClick = actions.changeServer) { Text("Use a different backend") }
}

@Composable
private fun LoginForm(
    state: AccessState,
    actions: AccessActions,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var totp by rememberSaveable { mutableStateOf("") }
    SectionTitle("Sign in")
    state.notice?.let { Banner(it) }
    Field("Username", user, { user = it }, modifier = Modifier.testTag("username"))
    Field("Password", pass, { pass = it }, password = true, modifier = Modifier.testTag("password"))
    Field("Authenticator code (if enabled)", totp, { totp = it }, number = true)
    Button(onClick = { actions.login(user, pass, totp.ifBlank { null }) }, enabled = state.action != ActionState.Running, modifier = Modifier.testTag("signin")) { Text("Sign in") }
    TextButton(onClick = actions.showRecovery) { Text("Use a recovery code") }
    TextButton(onClick = actions.changeServer) { Text("Change backend address") }
}

@Composable
private fun RecoveryForm(actions: AccessActions) {
    var user by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    SectionTitle("Recover access")
    Text("Recovery codes work offline from the authenticator. Using one signs out every other session and disables TOTP.")
    Field("Username", user, { user = it })
    Field("Recovery code", code, { code = it })
    Field("New password (12+ characters)", pass, { pass = it }, password = true)
    Button(onClick = { actions.recover(user, code, pass) }) { Text("Recover") }
    TextButton(onClick = actions.showLogin) { Text("Back to sign in") }
}

@Composable
private fun RecoveryCodes(
    state: AccessState,
    actions: AccessActions,
) {
    SectionTitle("Save your recovery codes")
    Banner(state.notice ?: "Store these codes offline. They are shown only once.", BannerKind.WARNING)
    state.recoveryCodes.forEach { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp).testTag("code")) }
    Button(onClick = actions.codesSaved) { Text("I have stored them safely") }
}

fun deviceTimezone(): String = ZoneId.systemDefault().id
