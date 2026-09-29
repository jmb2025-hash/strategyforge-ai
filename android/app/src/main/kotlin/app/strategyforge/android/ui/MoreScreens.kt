package app.strategyforge.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.strategyforge.android.BuildConfig
import app.strategyforge.android.core.format.Formatters

@Composable
fun MoreScreen(
    nav: NavHostController,
    session: SessionViewModel,
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("More")
        listOf(
            "engine" to "Market data and background running",
            "ai-providers" to "AI providers and keys",
            "research" to "AI research",
            "budget" to "AI budget",
            "reports" to "Reports",
            "exports" to "Exports (CSV / JSON)",
            "backups" to "Backups",
            "settings" to "Settings and privacy",
            "diagnostics" to "Diagnostics",
            "emergency" to "Emergency controls",
        ).forEach { (route, label) ->
            OutlinedButton(onClick = { nav.navigate(route) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
        SectionTitle("About")
        Text(
            "StrategyForge ${BuildConfig.VERSION_NAME}. Private, single-owner paper-trading app that runs entirely on this phone. " +
                "All trading is simulated: there is no real money, brokerage connection or order routing.",
        )
    }
}

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    fmt: Formatters,
    session: SessionViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    var redact by rememberSaveable { mutableStateOf(vm.config.redactUnlocked) }
    var secure by rememberSaveable { mutableStateOf(vm.config.secureScreen) }
    var lock by rememberSaveable { mutableStateOf(vm.config.appLock) }
    var showReauth by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Settings")
        ResourceContent(state, fmt, vm::refresh) { s ->
            var tz by rememberSaveable(s.version) { mutableStateOf(s.timezone) }
            var currency by rememberSaveable(s.version) { mutableStateOf(s.displayCurrency) }
            var showCad by rememberSaveable(s.version) { mutableStateOf(s.showCadEquivalent) }
            var theme by rememberSaveable(s.version) { mutableStateOf(s.theme) }
            Field("Timezone (IANA, e.g. America/Halifax)", tz, { tz = it })
            Row {
                listOf("USD", "CAD").forEach { c ->
                    FilterChip(selected = currency == c, onClick = { currency = c }, label = { Text(c) })
                    Spacer(Modifier.width(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showCad, onCheckedChange = { showCad = it })
                Spacer(Modifier.width(8.dp))
                Text("Show CAD equivalents")
            }
            Row {
                listOf("SYSTEM", "LIGHT", "DARK").forEach { t ->
                    FilterChip(selected = theme == t, onClick = { theme = t }, label = { Text(t.lowercase()) })
                    Spacer(Modifier.width(8.dp))
                }
            }
            Button(onClick = { vm.save(s, tz.trim(), currency, showCad, theme) }) { Text("Save") }
        }
        SectionTitle("Privacy on this device")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = redact, onCheckedChange = {
                redact = it
                vm.config.redactUnlocked = it
            })
            Spacer(Modifier.width(8.dp))
            Text("Hide notification details even when unlocked (lock screen is always redacted)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = secure, onCheckedChange = {
                secure = it
                vm.config.secureScreen = it
            })
            Spacer(Modifier.width(8.dp))
            Text("Block screenshots and recent-apps previews (applies on next launch)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = lock, onCheckedChange = {
                lock = it
                vm.config.appLock = it
            })
            Spacer(Modifier.width(8.dp))
            Text("Ask for the screen lock when the app opens")
        }
        ActionFeedback(action, onReauth = { showReauth = true })
    }
    if (showReauth) {
        ReauthDialog({ showReauth = false }) { pw, totp ->
            showReauth = false
            session.reauthenticate(pw, totp) { vm.clearAction() }
        }
    }
}
