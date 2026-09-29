package app.strategyforge.android.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.Provider
import app.strategyforge.android.core.model.ProviderType
import app.strategyforge.android.core.model.RuntimeState
import app.strategyforge.android.engine.EngineService
import app.strategyforge.android.platform.LocalConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ------------------------------------------------------------------ AI providers (D-030)

data class AiProvidersState(
    val types: List<ProviderType> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val loading: Boolean = true,
    val loadError: String? = null,
)

/** AI providers the phone calls directly; keys go to the Android Keystore, never to the database. */
@HiltViewModel
class AiProvidersViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : AccountViewModel() {
        private val _state = MutableStateFlow(AiProvidersState())
        val state: StateFlow<AiProvidersState> = _state.asStateFlow()

        init {
            load()
        }

        override fun load() {
            viewModelScope.launch {
                runCatching { repo.providerTypes() to repo.providers().let { flow -> flowValue(flow) } }
                    .onSuccess { (types, providers) -> _state.update { it.copy(types = types, providers = providers.filter { p -> p.kind == "AI" }, loading = false, loadError = null) } }
                    .onFailure { e -> _state.update { it.copy(loading = false, loadError = Repository.message(e)) } }
            }
        }

        private suspend fun flowValue(flow: kotlinx.coroutines.flow.Flow<app.strategyforge.android.core.cache.Resource<List<Provider>>>): List<Provider> {
            var out: List<Provider> = emptyList()
            flow.collect { r ->
                when (r) {
                    is app.strategyforge.android.core.cache.Resource.Data -> out = r.value
                    is app.strategyforge.android.core.cache.Resource.Failure -> throw r.error
                    else -> Unit
                }
            }
            return out
        }

        fun create(
            type: String,
            name: String,
            settings: Map<String, String>,
            key: String,
        ) = act("Provider added") { repo.createProvider(type, name, settings, key) }

        fun setKey(
            id: String,
            key: String?,
        ) = act(if (key == null) "Key removed" else "Key replaced") { repo.setProviderKey(id, key) }

        fun setActive(
            id: String,
            active: Boolean,
        ) = act(if (active) "Provider enabled" else "Provider disabled") { repo.setProviderActive(id, active) }

        fun test(id: String) = act("Connection test finished") { repo.testProvider(id) }
    }

@Composable
fun AiProvidersScreen(
    vm: AiProvidersViewModel,
    session: SessionViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        AiProvidersContent(state, vm::create, vm::setKey, vm::setActive, vm::test)
        ReauthHostFor(action, session, vm)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiProvidersContent(
    state: AiProvidersState,
    onCreate: (type: String, name: String, settings: Map<String, String>, key: String) -> Unit,
    onSetKey: (id: String, key: String?) -> Unit,
    onSetActive: (id: String, active: Boolean) -> Unit,
    onTest: (id: String) -> Unit,
) {
    val context = LocalContext.current
    SectionTitle("AI providers")
    Text(
        "AI research runs on this phone and sends your prompt straight to the provider you choose. Google Gemini has a free tier: create a key, paste it below, and keep the prices at 0. " +
            "Keys are encrypted with this phone's secure key store and never leave it except in requests to that provider.",
    )
    when {
        state.loading -> Loading()
        state.loadError != null -> Banner(state.loadError, BannerKind.ERROR)
        state.providers.isEmpty() -> Banner("No AI provider yet. Add Gemini below to start AI research.", BannerKind.WARNING)
    }
    state.providers.forEach { p ->
        SfCard(Modifier.testTag("provider")) {
            LabelValue(p.displayName, p.providerType)
            p.settings["model"]?.let { LabelValue("Model", it) }
            LabelValue("Key", if (p.credentialConfigured) "stored (…${p.credentialFingerprint?.takeLast(4) ?: ""})" else "missing")
            p.lastTestStatus?.let { LabelValue("Last test", it) }
            p.lastTestDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = p.active, onCheckedChange = { onSetActive(p.id, it) })
                Spacer(Modifier.width(8.dp))
                Text(if (p.active) "Enabled" else "Disabled")
            }
            var newKey by rememberSaveable(p.id) { mutableStateOf("") }
            Field("New key", newKey, { newKey = it }, password = true)
            Row {
                TextButton(onClick = { onTest(p.id) }) { Text("Test") }
                TextButton(onClick = {
                    onSetKey(p.id, newKey.trim())
                    newKey = ""
                }, enabled = newKey.isNotBlank()) { Text("Replace key") }
                if (p.credentialConfigured) TextButton(onClick = { onSetKey(p.id, null) }) { Text("Remove key") }
            }
        }
    }
    if (state.types.isEmpty()) return
    SectionTitle("Add a provider")
    var type by rememberSaveable { mutableStateOf("GEMINI") }
    val t = state.types.firstOrNull { it.providerType == type } ?: state.types.first()
    var name by rememberSaveable(type) { mutableStateOf(t.label.substringBefore(" (")) }
    var model by rememberSaveable(type) { mutableStateOf(t.presets["model"].orEmpty()) }
    var inPrice by rememberSaveable(type) { mutableStateOf(t.presets["inputPricePerMillionTokensUsd"].orEmpty()) }
    var outPrice by rememberSaveable(type) { mutableStateOf(t.presets["outputPricePerMillionTokensUsd"].orEmpty()) }
    var key by rememberSaveable(type) { mutableStateOf("") }
    FlowRow {
        state.types.forEach { option ->
            FilterChip(selected = option.providerType == t.providerType, onClick = { type = option.providerType }, label = { Text(option.label.substringBefore(" (")) })
            Spacer(Modifier.width(8.dp))
        }
    }
    Text(t.label, style = MaterialTheme.typography.bodySmall)
    t.keyUrl?.let { url ->
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                // No browser installed; the address is shown below.
            }
        }) { Text("Get a key: $url") }
    }
    Field("Name", name, { name = it })
    Field("Model", model, { model = it })
    Field("Input price, USD per million tokens (0 for free tier)", inPrice, { inPrice = it }, number = true)
    Field("Output price, USD per million tokens (0 for free tier)", outPrice, { outPrice = it }, number = true)
    Field("API key", key, { key = it }, password = true)
    Button(
        onClick = {
            val settings = t.presets + mapOf("model" to model.trim(), "inputPricePerMillionTokensUsd" to inPrice.trim(), "outputPricePerMillionTokensUsd" to outPrice.trim())
            onCreate(t.providerType, name.trim(), settings, key.trim())
            key = ""
        },
        enabled = name.isNotBlank() && model.isNotBlank() && inPrice.isNotBlank() && outPrice.isNotBlank() && key.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("add-provider"),
    ) { Text("Add provider") }
}

/** Re-runs a refused action after the device-lock confirmation. */
@Composable
fun ReauthHostFor(
    action: app.strategyforge.android.core.state.ActionState,
    session: SessionViewModel,
    vm: AccountViewModel,
) {
    var show by rememberSaveable { mutableStateOf(false) }
    ActionFeedback(action, onReauth = { show = true })
    if (show) {
        ReauthDialog({ show = false }) { pw, totp ->
            show = false
            session.reauthenticate(pw, totp) { vm.retryAfterReauth() }
        }
    }
}

// ------------------------------------------------------------------ market data and background (D-027)

data class EngineUiState(
    val runtime: RuntimeState? = null,
    val runInBackground: Boolean = true,
    val keepAwake: Boolean = true,
    val batteryUnrestricted: Boolean = false,
    val loadError: String? = null,
)

@HiltViewModel
class EngineViewModel
    @Inject
    constructor(
        private val app: Application,
        private val repo: Repository,
        private val config: LocalConfig,
    ) : AccountViewModel() {
        private val _state = MutableStateFlow(EngineUiState())
        val state: StateFlow<EngineUiState> = _state.asStateFlow()

        init {
            load()
        }

        override fun load() {
            viewModelScope.launch {
                val battery = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
                runCatching { repo.runtime() }
                    .onSuccess { r -> _state.update { it.copy(runtime = r, loadError = null) } }
                    .onFailure { e -> _state.update { it.copy(loadError = Repository.message(e)) } }
                _state.update { it.copy(runInBackground = config.runInBackground, keepAwake = config.keepAwake, batteryUnrestricted = battery) }
            }
        }

        fun setMode(mode: String) = act(if (mode == "LIVE") "Live market data on" else "Demo mode on") { repo.setMarketMode(mode) }

        fun setDemoSpeed(minutes: Int) = act("Demo speed changed") { repo.setDemoSpeed(minutes) }

        fun setRunInBackground(on: Boolean) {
            config.runInBackground = on
            if (on) EngineService.start(app) else EngineService.stop(app)
            load()
        }

        fun setKeepAwake(on: Boolean) {
            config.keepAwake = on
            EngineService.start(app)
            load()
        }
    }

@Composable
fun EngineScreen(
    vm: EngineViewModel,
    fmt: Formatters,
    session: SessionViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.load() }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        EngineContent(
            state,
            fmt,
            onMode = vm::setMode,
            onDemoSpeed = vm::setDemoSpeed,
            onRunInBackground = vm::setRunInBackground,
            onKeepAwake = vm::setKeepAwake,
            onBattery = { requestUnrestrictedBattery(context) },
        )
        ReauthHostFor(action, session, vm)
    }
}

@SuppressLint("BatteryLife")
private fun requestUnrestrictedBattery(context: android.content.Context) {
    // The owner asked for continuous background paper trading; Android asks them to confirm.
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EngineContent(
    state: EngineUiState,
    fmt: Formatters,
    onMode: (String) -> Unit,
    onDemoSpeed: (Int) -> Unit,
    onRunInBackground: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onBattery: () -> Unit,
) {
    SectionTitle("Market data")
    state.loadError?.let { Banner(it, BannerKind.ERROR) }
    val r = state.runtime
    val mode = r?.marketMode ?: "DEMO"
    Row {
        listOf("DEMO" to "Demo (replay)", "LIVE" to "Live").forEach { (m, label) ->
            FilterChip(selected = mode == m, onClick = { if (mode != m) onMode(m) }, label = { Text(label) }, modifier = Modifier.testTag("mode-$m"))
            Spacer(Modifier.width(8.dp))
        }
    }
    if (mode == "LIVE") {
        Text("Crypto uses real-time public prices from Coinbase (no account or key). All orders stay simulated paper trades.")
        Text("Stocks need a market-data key and are not available in live mode yet.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Demo mode replays recorded market data so you can try every feature safely. Market time moves forward on each engine tick.")
        r?.marketTime?.let { LabelValue("Market time", fmt.dateTime(it)) }
        Text("Demo speed (replay minutes per ${r?.tickSeconds ?: 5}-second tick)")
        FlowRow {
            listOf(0 to "Paused", 1 to "1 min", 5 to "5 min", 15 to "15 min", 60 to "1 hour").forEach { (m, label) ->
                FilterChip(selected = r?.demoStepMinutes == m, onClick = { onDemoSpeed(m) }, label = { Text(label) })
                Spacer(Modifier.width(8.dp))
            }
        }
    }
    SectionTitle("Background running")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = state.runInBackground, onCheckedChange = onRunInBackground, modifier = Modifier.testTag("run-in-background"))
        Spacer(Modifier.width(8.dp))
        Text("Keep paper trading when the app is closed (shows a small persistent notification)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = state.keepAwake, onCheckedChange = onKeepAwake, enabled = state.runInBackground)
        Spacer(Modifier.width(8.dp))
        Text("Keep working with the screen off (uses more battery)")
    }
    if (state.batteryUnrestricted) {
        Banner("Battery: unrestricted. Android will not pause the engine.")
    } else {
        Banner("Battery optimization is on, so Android may pause the engine when the phone is idle.", BannerKind.WARNING)
        OutlinedButton(onClick = onBattery, modifier = Modifier.fillMaxWidth().testTag("battery")) { Text("Allow unrestricted battery use") }
    }
    Text(
        "Some phones also close background apps on their own. If trading stops while the app is closed, open the recent-apps screen, press and hold StrategyForge and choose \"Lock\" or \"Keep open\".",
        style = MaterialTheme.typography.bodySmall,
    )
}
