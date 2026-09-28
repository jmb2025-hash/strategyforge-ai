package app.strategyforge.android.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.api.ServerUrl
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.Dashboard
import app.strategyforge.android.core.data.OrderDraft
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.model.Backtest
import app.strategyforge.android.core.model.Diagnostics
import app.strategyforge.android.core.model.Disclosure
import app.strategyforge.android.core.model.Notification
import app.strategyforge.android.core.model.Order
import app.strategyforge.android.core.model.Page
import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Provider
import app.strategyforge.android.core.model.Recommendation
import app.strategyforge.android.core.model.ReportView
import app.strategyforge.android.core.model.ResearchDetail
import app.strategyforge.android.core.model.ResearchSession
import app.strategyforge.android.core.model.Settings
import app.strategyforge.android.core.model.Strategy
import app.strategyforge.android.core.model.StrategyDetail
import app.strategyforge.android.core.state.AccessPresenter
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.EmergencyPresenter
import app.strategyforge.android.core.state.RecommendationPresenter
import app.strategyforge.android.core.state.toFailure
import app.strategyforge.android.platform.AllowInsecureLocal
import app.strategyforge.android.platform.LocalConfig
import app.strategyforge.android.push.PushSetup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Loads a cached resource and exposes it as immutable UI state (unidirectional data flow). */
abstract class ResourceViewModel<T> : ViewModel() {
    private val _state = MutableStateFlow<Resource<T>>(Resource.Loading)
    val state: StateFlow<Resource<T>> = _state.asStateFlow()
    private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
    val action: StateFlow<ActionState> = _action.asStateFlow()
    private var job: Job? = null

    protected abstract fun source(): Flow<Resource<T>>

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { source().collect { _state.value = it } }
    }

    /** Runs a mutation, shows its outcome and reloads authoritative state. */
    protected fun act(
        success: String,
        block: suspend () -> Unit,
    ) {
        if (_action.value == ActionState.Running) return
        _action.value = ActionState.Running
        viewModelScope.launch {
            _action.value =
                runCatching { block() }.fold(
                    { ActionState.Done(success) },
                    { it.toFailure() },
                )
            refresh()
        }
    }

    fun clearAction() {
        _action.value = ActionState.Idle
    }
}

@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val app: Application,
        private val repo: Repository,
        val config: LocalConfig,
        @AllowInsecureLocal private val allowInsecureLocal: Boolean,
    ) : ViewModel() {
        val access =
            AccessPresenter(
                viewModelScope,
                repo,
                saveServer = { config.serverUrl = it },
                validateServer = { ServerUrl.validate(it, allowInsecureLocal) },
                initialServer = config.serverUrl,
                signedIn = repo.signedIn(),
            )
        private val _timezone = MutableStateFlow<String?>(null)
        val timezone: StateFlow<String?> = _timezone.asStateFlow()
        private val _reauth = MutableStateFlow<ActionState>(ActionState.Idle)
        val reauth: StateFlow<ActionState> = _reauth.asStateFlow()

        /** After sign-in: register this device (and FCM when configured) and load display preferences. */
        fun onSignedIn() {
            viewModelScope.launch {
                runCatching { PushSetup.register(app, repo, config) }
                repo.settings().collect { r -> if (r is Resource.Data) _timezone.value = r.value.timezone }
            }
        }

        fun reauthenticate(
            password: String,
            totp: String?,
            then: () -> Unit,
        ) {
            _reauth.value = ActionState.Running
            viewModelScope.launch {
                runCatching { repo.reauthenticate(password, totp) }
                    .onSuccess {
                        _reauth.value = ActionState.Idle
                        then()
                    }.onFailure { _reauth.value = it.toFailure() }
            }
        }

        fun signOut() {
            viewModelScope.launch {
                runCatching { repo.logout() }
                repo.clearCache()
                access.signedOut()
            }
        }
    }

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ResourceViewModel<Dashboard>() {
        override fun source() = repo.dashboard()

        init {
            refresh()
        }
    }

@HiltViewModel
class EmergencyViewModel
    @Inject
    constructor(
        repo: Repository,
    ) : ViewModel() {
        val presenter = EmergencyPresenter(viewModelScope, repo).also { it.load() }
    }

@HiltViewModel
class StrategiesViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ResourceViewModel<List<Strategy>>() {
        override fun source() = repo.strategies()

        init {
            refresh()
        }

        private val _imported = MutableStateFlow<String?>(null)
        val imported: StateFlow<String?> = _imported.asStateFlow()

        fun import(json: String) =
            act("Strategy imported") {
                val r = repo.importStrategy(json)
                _imported.value = r.strategy.id
            }
    }

@HiltViewModel
class StrategyDetailViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ResourceViewModel<StrategyDetail>() {
        val id: String = checkNotNull(saved["id"])
        private val _backtests = MutableStateFlow<List<Backtest>>(emptyList())
        val backtests: StateFlow<List<Backtest>> = _backtests.asStateFlow()
        private val _disclosure = MutableStateFlow<Disclosure?>(null)
        val disclosure: StateFlow<Disclosure?> = _disclosure.asStateFlow()
        private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
        val portfolios: StateFlow<List<Portfolio>> = _portfolios.asStateFlow()

        override fun source() = repo.strategy(id)

        init {
            refresh()
            loadExtras()
        }

        fun loadExtras() {
            viewModelScope.launch {
                runCatching { _backtests.value = repo.backtests(id) }
                runCatching { _disclosure.value = repo.disclosure() }
                repo.portfolios().collect { r -> if (r is Resource.Data) _portfolios.value = r.value.filter { it.status == "ACTIVE" } }
            }
        }

        fun revalidate() = act("Validation re-run") { repo.revalidate(id) }

        fun backtest(
            from: String,
            to: String,
            capital: String,
        ) = act("Backtest started; results appear when it completes") {
            repo.runBacktest(id, from, to, capital)
            _backtests.value = repo.backtests(id)
        }

        fun activate(
            portfolioId: String,
            allocation: String,
            autonomous: Boolean,
            disclosureAccepted: Boolean,
        ) = act(if (autonomous) "Autonomous paper trading enabled" else "Recommendation Mode enabled") {
            repo.activate(id, portfolioId, allocation, autonomous, if (autonomous && disclosureAccepted) _disclosure.value?.version else null)
        }

        fun deactivate() = act("Strategy paused") { repo.deactivate(id) }
    }

@HiltViewModel
class PortfolioViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ResourceViewModel<PortfolioSummary>() {
        private val requested: String? = saved["id"]
        private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
        val portfolios: StateFlow<List<Portfolio>> = _portfolios.asStateFlow()
        private val _selected = MutableStateFlow(requested)
        val selected: StateFlow<String?> = _selected.asStateFlow()
        private val _orders = MutableStateFlow<Resource<Page<Order>>>(Resource.Loading)
        val orders: StateFlow<Resource<Page<Order>>> = _orders.asStateFlow()

        override fun source(): Flow<Resource<PortfolioSummary>> {
            val id = _selected.value
            return if (id == null) kotlinx.coroutines.flow.flowOf(Resource.Loading) else repo.portfolioSummary(id)
        }

        init {
            viewModelScope.launch {
                repo.portfolios().collect { r ->
                    if (r is Resource.Data) {
                        _portfolios.value = r.value
                        if (_selected.value == null) {
                            _selected.value = r.value.firstOrNull { it.status == "ACTIVE" }?.id
                            reload()
                        }
                    }
                }
            }
            reload()
        }

        fun select(id: String) {
            _selected.value = id
            reload()
        }

        fun reload() {
            refresh()
            val id = _selected.value ?: return
            viewModelScope.launch { repo.orders(id).collect { _orders.value = it } }
        }

        fun createPortfolio(
            name: String,
            balance: String,
        ) = act("Portfolio created") {
            val p = repo.createPortfolio(name, balance)
            _selected.value = p.id
        }

        fun placeOrder(d: OrderDraft) = act("Paper order submitted") { repo.placeOrder(d) }

        fun cancel(orderId: String) = act("Order cancelled") { repo.cancelOrder(orderId) }
    }

@HiltViewModel
class RecommendationsViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ResourceViewModel<Page<Recommendation>>() {
        private val _filter = MutableStateFlow<String?>("PENDING")
        val filter: StateFlow<String?> = _filter.asStateFlow()

        override fun source() = repo.recommendations(_filter.value)

        init {
            refresh()
        }

        fun setFilter(status: String?) {
            _filter.value = status
            refresh()
        }
    }

@HiltViewModel
class RecommendationDetailViewModel
    @Inject
    constructor(
        repo: Repository,
        saved: SavedStateHandle,
    ) : ViewModel() {
        val presenter = RecommendationPresenter(viewModelScope, repo, checkNotNull(saved["id"])).also { it.load() }
    }

@HiltViewModel
class InboxViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ResourceViewModel<Page<Notification>>() {
        override fun source() = repo.notifications()

        init {
            refresh()
        }

        fun markRead(id: String) = act("Marked as read") { repo.markRead(id) }

        fun markAllRead() = act("All notifications marked as read") { repo.markAllRead() }
    }

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ResourceViewModel<Diagnostics>() {
        override fun source() = repo.diagnostics()

        init {
            refresh()
        }
    }

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repo: Repository,
        val config: LocalConfig,
    ) : ResourceViewModel<Settings>() {
        private val _providers = MutableStateFlow<List<Provider>>(emptyList())
        val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

        override fun source() = repo.settings()

        init {
            refresh()
            viewModelScope.launch { repo.providers().collect { r -> if (r is Resource.Data) _providers.value = r.value } }
        }

        fun save(
            current: Settings,
            timezone: String,
            currency: String,
            showCad: Boolean,
            theme: String,
        ) = act("Settings saved") { repo.updateSettings(current, timezone, currency, showCad, theme) }

        fun testProvider(id: String) = act("Provider diagnostics finished") { repo.testProvider(id) }
    }

@HiltViewModel
class ResearchListViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ResourceViewModel<List<ResearchSession>>() {
        private val _providers = MutableStateFlow<List<Provider>>(emptyList())
        val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

        override fun source() = repo.research()

        init {
            refresh()
            viewModelScope.launch { repo.providers().collect { r -> if (r is Resource.Data) _providers.value = r.value.filter { it.kind == "AI" } } }
        }

        fun create(
            providerId: String,
            title: String,
            assetClass: String,
            symbols: List<String>,
            timeframe: String,
            horizon: String,
            approach: String,
            prompt: String,
            retrieval: Boolean,
            maxRequests: Int,
            maxCost: String,
        ) = act("Research session created") {
            repo.createResearch(
                buildJsonObject {
                    put("providerId", providerId)
                    put("title", title)
                    put("assetClass", assetClass)
                    put("universe", JsonArray(symbols.map { JsonPrimitive(it) }))
                    put("timeframe", timeframe)
                    put("horizon", horizon)
                    put("approach", approach)
                    put("prompt", prompt)
                    put("retrieval", retrieval)
                    put("maxRequests", maxRequests)
                    put("maxCostUsd", maxCost)
                },
            )
        }
    }

@HiltViewModel
class ResearchDetailViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ViewModel() {
        private val id: String = checkNotNull(saved["id"])
        private val _detail = MutableStateFlow<ResearchDetail?>(null)
        val detail: StateFlow<ResearchDetail?> = _detail.asStateFlow()
        private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
        val action: StateFlow<ActionState> = _action.asStateFlow()

        init {
            load()
        }

        fun load() {
            viewModelScope.launch { runCatching { _detail.value = repo.researchDetail(id) }.onFailure { _action.value = it.toFailure() } }
        }

        private fun act(block: suspend () -> ResearchDetail) {
            _action.value = ActionState.Running
            viewModelScope.launch {
                runCatching { block() }
                    .onSuccess {
                        _detail.value = it
                        _action.value = ActionState.Done("Request accepted; refresh to see the result")
                    }.onFailure { _action.value = it.toFailure() }
            }
        }

        fun run() = act { repo.runResearch(id) }

        fun review(approve: Boolean) = act { repo.reviewResearch(id, approve, null) }

        fun compile() = act { repo.compileResearch(id) }
    }

/** FR-103/FR-104: report for the requested portfolio, or the first active one. */
@HiltViewModel
class ReportsViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ResourceViewModel<ReportView>() {
        private val requested: String? = saved["id"]
        private val _noPortfolio = MutableStateFlow(false)
        val noPortfolio: StateFlow<Boolean> = _noPortfolio.asStateFlow()

        override fun source(): Flow<Resource<ReportView>> =
            flow {
                val id =
                    requested ?: run {
                        val portfolios = repo.portfolios().first { it !is Resource.Loading }
                        when (portfolios) {
                            is Resource.Data -> portfolios.value.firstOrNull { it.status == "ACTIVE" }?.id
                            is Resource.Failure -> return@flow emit(Resource.Failure(portfolios.error))
                            Resource.Loading -> null
                        }
                    }
                _noPortfolio.value = id == null
                if (id != null) emitAll(repo.report(id))
            }

        init {
            refresh()
        }
    }
