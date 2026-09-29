package app.strategyforge.android.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.api.ApiError
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
import app.strategyforge.android.core.model.Scorecard
import app.strategyforge.android.core.model.Settings
import app.strategyforge.android.core.model.Slot
import app.strategyforge.android.core.model.Strategy
import app.strategyforge.android.core.model.StrategyDetail
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.EmergencyPresenter
import app.strategyforge.android.core.state.RecommendationPresenter
import app.strategyforge.android.core.state.toFailure
import app.strategyforge.android.platform.LocalConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

/** App-wide state: display preferences and the device-lock confirmation for protected actions. */
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val repo: Repository,
        val config: LocalConfig,
    ) : ViewModel() {
        private val _timezone = MutableStateFlow<String?>(null)
        val timezone: StateFlow<String?> = _timezone.asStateFlow()
        private val _reauth = MutableStateFlow<ActionState>(ActionState.Idle)
        val reauth: StateFlow<ActionState> = _reauth.asStateFlow()

        fun onStarted() {
            viewModelScope.launch {
                repo.settings().collect { r -> if (r is Resource.Data) _timezone.value = r.value.timezone }
            }
        }

        /**
         * Called after the device-lock prompt succeeded (see ReauthDialog); it tells the engine the
         * owner confirmed recently (D-029). The password arguments are unused on the phone.
         */
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

        private val _slots = MutableStateFlow<List<Slot>>(emptyList())

        /** The crypto and stock strategies running now (D-035). */
        val slots: StateFlow<List<Slot>> = _slots.asStateFlow()

        init {
            refresh()
            loadSlots()
        }

        fun loadSlots() {
            viewModelScope.launch { runCatching { _slots.value = repo.slots() } }
        }

        fun stop(strategyId: String) =
            act("Strategy stopped") {
                repo.deactivate(strategyId)
                _slots.value = repo.slots()
            }

        private val _imported = MutableStateFlow<String?>(null)
        val imported: StateFlow<String?> = _imported.asStateFlow()

        fun import(json: String) =
            act("Strategy imported") {
                val r = repo.importStrategy(json)
                _imported.value = r.strategy.id
            }
    }

/** Scorecards per asset class and the "build me a better strategy" request (D-037). */
@HiltViewModel
class ScorecardsViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ViewModel() {
        private val _asset = MutableStateFlow("CRYPTO")
        val asset: StateFlow<String> = _asset.asStateFlow()
        private val _cards = MutableStateFlow<List<Scorecard>?>(null)
        val cards: StateFlow<List<Scorecard>?> = _cards.asStateFlow()
        private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
        val action: StateFlow<ActionState> = _action.asStateFlow()
        private val _started = MutableStateFlow<String?>(null)

        /** The research conversation just started, so the screen can open it. */
        val started: StateFlow<String?> = _started.asStateFlow()

        init {
            load()
        }

        fun select(assetClass: String) {
            _asset.value = assetClass
            load()
        }

        private fun load() {
            _cards.value = null
            val a = _asset.value
            viewModelScope.launch {
                runCatching { repo.scorecards(a) }
                    .onSuccess { if (_asset.value == a) _cards.value = it }
                    .onFailure {
                        _cards.value = emptyList()
                        _action.value = it.toFailure()
                    }
            }
        }

        fun buildBetter() {
            if (_action.value == ActionState.Running) return
            _action.value = ActionState.Running
            viewModelScope.launch {
                runCatching { repo.buildBetterStrategy(_asset.value) }
                    .onSuccess {
                        _action.value = ActionState.Done("The AI is working on a combined strategy")
                        _started.value = it.session.id
                    }.onFailure { _action.value = it.toFailure() }
            }
        }

        fun consumeStarted() {
            _started.value = null
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
        private val _scorecard = MutableStateFlow<Scorecard?>(null)

        /** This strategy's paper and backtest results (D-037). */
        val scorecard: StateFlow<Scorecard?> = _scorecard.asStateFlow()

        override fun source() = repo.strategy(id)

        init {
            refresh()
            loadExtras()
        }

        fun loadExtras() {
            viewModelScope.launch {
                runCatching { _backtests.value = repo.backtests(id) }
                runCatching { _scorecard.value = repo.scorecard(id) }
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

        private val _slotConflict = MutableStateFlow<SlotConflict?>(null)

        /** Set when another strategy of the same asset class is running (D-035): ask keep or close. */
        val slotConflict: StateFlow<SlotConflict?> = _slotConflict.asStateFlow()

        fun activate(
            portfolioId: String,
            allocation: String,
            autonomous: Boolean,
            disclosureAccepted: Boolean,
            positions: String? = null,
        ): Unit =
            act(if (autonomous) "Autonomous paper trading enabled" else "Notifications mode enabled") {
                try {
                    val a = repo.activate(id, portfolioId, allocation, autonomous, if (autonomous && disclosureAccepted) _disclosure.value?.version else null, positions)
                    _replaced.value = a.replacedStrategyName?.let { ReplaceOutcome(it, a) }
                } catch (e: ApiError.Http) {
                    if (e.code != "slot-occupied") throw e
                    _slotConflict.value = SlotConflict.from(e) { keep -> activate(portfolioId, allocation, autonomous, disclosureAccepted, if (keep) "KEEP" else "CLOSE") }
                }
            }

        private val _replaced = MutableStateFlow<ReplaceOutcome?>(null)

        /** What happened to the replaced strategy's positions, shown once after a switch. */
        val replaced: StateFlow<ReplaceOutcome?> = _replaced.asStateFlow()

        fun resolveSlot(keep: Boolean?) {
            val c = _slotConflict.value ?: return
            _slotConflict.value = null
            if (keep != null) c.retry(keep)
        }

        /** A backtest over recent history sized to the strategy's timeframe (Coinbase serves 20 pages of 300 bars). */
        fun quickBacktest(timeframe: String?) =
            act("Backtest finished") {
                val end = repo.runtime().marketTime?.let { java.time.Instant.parse(it) } ?: java.time.Instant.now()
                val days =
                    when (timeframe) {
                        "1m" -> 3L
                        "5m" -> 14L
                        "15m" -> 30L
                        "1h", "4h" -> 180L
                        else -> 730L
                    }
                repo.runBacktest(id, end.minus(java.time.Duration.ofDays(days)).toString(), end.toString(), "100000")
                _backtests.value = repo.backtests(id)
            }

        fun deactivate() = act("Strategy stopped") { repo.deactivate(id) }
    }

/** Another strategy holds the slot; [retry] re-sends the activation with KEEP (true) or CLOSE (false). */
data class SlotConflict(
    val currentName: String,
    val openPositions: List<String>,
    val retry: (Boolean) -> Unit,
) {
    companion object {
        fun from(
            e: ApiError.Http,
            retry: (Boolean) -> Unit,
        ): SlotConflict {
            val p = e.properties
            val name = p?.get("currentStrategyName")?.jsonPrimitive?.contentOrNull ?: "The current strategy"
            val positions =
                (p?.get("openPositions") as? JsonArray)
                    ?.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        "${o["side"]?.jsonPrimitive?.contentOrNull?.lowercase()} ${o["quantity"]?.jsonPrimitive?.contentOrNull} ${o["symbol"]?.jsonPrimitive?.contentOrNull}"
                    }.orEmpty()
            return SlotConflict(name, positions, retry)
        }
    }
}

data class ReplaceOutcome(
    val replacedName: String,
    val activation: app.strategyforge.android.core.model.Activation,
)

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

/** Research conversations (D-034): the list, and starting one from a single message. */
@HiltViewModel
class ResearchListViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ResourceViewModel<List<ResearchSession>>() {
        private val _started = MutableStateFlow<String?>(null)

        /** The conversation just started, so the screen can open it. */
        val started: StateFlow<String?> = _started.asStateFlow()

        override fun source() = repo.research()

        init {
            refresh()
        }

        fun start(
            message: String,
            assetClass: String,
        ) = act("Research started") {
            _started.value = repo.startConversation(message.trim(), assetClass).session.id
        }

        fun consumeStarted() {
            _started.value = null
        }
    }

/**
 * One research conversation. The AI answers in the background, so while a turn is running the
 * screen checks again every few seconds.
 */
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
        private var polling: Job? = null

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                runCatching { show(repo.researchDetail(id)) }.onFailure { _action.value = it.toFailure() }
            }
        }

        private fun show(d: ResearchDetail) {
            _detail.value = d
            if (d.session.status == "RUNNING") poll()
        }

        private fun poll() {
            if (polling?.isActive == true) return
            polling =
                viewModelScope.launch {
                    while (true) {
                        delay(POLL_MS)
                        val d = runCatching { repo.researchDetail(id) }.getOrNull() ?: continue
                        _detail.value = d
                        if (d.session.status != "RUNNING") break
                    }
                }
        }

        private fun act(
            done: String,
            block: suspend () -> ResearchDetail,
        ) {
            _action.value = ActionState.Running
            viewModelScope.launch {
                runCatching { block() }
                    .onSuccess {
                        show(it)
                        _action.value = ActionState.Done(done)
                    }.onFailure { _action.value = it.toFailure() }
            }
        }

        fun send(message: String) = act("Sent") { repo.sendResearchMessage(id, message.trim()) }

        /** Re-sends the last message (after a failed or interrupted answer). */
        fun retry() = act("Sent again") { repo.runResearch(id) }

        /** The owner confirms the research is reviewed, then it is compiled into a strategy. */
        fun compile() =
            act("Building the strategy…") {
                if (_detail.value?.session?.reviewStatus != "REVIEWED") repo.reviewResearch(id, true, "Reviewed in the conversation")
                repo.compileResearch(id)
            }

        fun clearAction() {
            _action.value = ActionState.Idle
        }

        private companion object {
            const val POLL_MS = 3000L
        }
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
