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
import app.strategyforge.android.core.model.CandleChart
import app.strategyforge.android.core.model.Diagnostics
import app.strategyforge.android.core.model.Disclosure
import app.strategyforge.android.core.model.EquityChart
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

        private val _equity = MutableStateFlow<EquityChart?>(null)

        /** The primary portfolio's last week, for the sparkline on the home card (D-038). */
        val equity: StateFlow<EquityChart?> = _equity.asStateFlow()

        init {
            refresh()
        }

        fun loadEquity(portfolioId: String) {
            viewModelScope.launch { runCatching { _equity.value = repo.equityChart(portfolioId, "1W") } }
        }

        private val _plans = MutableStateFlow<List<PlanSnapshot>>(emptyList())

        /** The running plans with their headline numbers, shown on Home (D-060). */
        val plans: StateFlow<List<PlanSnapshot>> = _plans.asStateFlow()

        fun loadPlans() {
            viewModelScope.launch {
                runCatching {
                    val slots = repo.slots().filter { it.strategy != null && it.activation != null }.map { RunningPlan.SlotPlan(it) }
                    val summaries = slots.map { it.portfolioId }.distinct().associateWith { id -> runCatching { repo.portfolioSummaryNow(id) }.getOrNull() }
                    val slotSnaps =
                        slots.map { p ->
                            val score = runCatching { repo.scorecard(p.strategyId) }.getOrNull()
                            val unrealized =
                                summaries[p.portfolioId]
                                    ?.positions
                                    ?.filter { it.symbol in p.symbols }
                                    ?.mapNotNull { it.unrealizedPnl?.toBigDecimalOrNull() }
                                    ?.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add) ?: java.math.BigDecimal.ZERO
                            val realized = score?.live?.realizedPnl?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                            PlanSnapshot.Slot(p, realized.add(unrealized).toPlainString(), p.symbols.size, score?.live?.closedTrades ?: 0, summaries[p.portfolioId]?.portfolio?.name)
                        }
                    val tsx = repo.tsxRuns().filter { it.status == "ACTIVE" }.map { PlanSnapshot.Tsx(RunningPlan.TsxPlan(it)) }
                    _plans.value = slotSnaps + tsx
                }
            }
        }
    }

/** A running plan's headline numbers for the Home screen (D-060). */
sealed interface PlanSnapshot {
    val plan: RunningPlan

    data class Slot(
        override val plan: RunningPlan.SlotPlan,
        /** Realized plus unrealized profit/loss in USD, as exact decimal text. */
        val profitLoss: String,
        val openPositions: Int,
        val closedTrades: Int,
        val portfolioName: String?,
    ) : PlanSnapshot

    data class Tsx(
        override val plan: RunningPlan.TsxPlan,
    ) : PlanSnapshot
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

        private val _tsxRuns = MutableStateFlow<List<app.strategyforge.android.core.model.TsxRun>>(emptyList())

        /** The TSX plans running in TSX slots, listed with the other slots (D-061). */
        val tsxRuns: StateFlow<List<app.strategyforge.android.core.model.TsxRun>> = _tsxRuns.asStateFlow()

        fun loadSlots() {
            viewModelScope.launch { runCatching { _slots.value = repo.slots() } }
            viewModelScope.launch { runCatching { _tsxRuns.value = repo.tsxRuns() } }
        }

        fun stopTsx(runId: String) =
            act("TSX plan stopped") {
                repo.tsxRunAction(runId, "stop")
                _tsxRuns.value = repo.tsxRuns()
            }

        private val _library = MutableStateFlow<List<app.strategyforge.android.core.model.LibraryPlan>>(emptyList())

        /** The built-in trading plans (D-049). */
        val library: StateFlow<List<app.strategyforge.android.core.model.LibraryPlan>> = _library.asStateFlow()

        fun loadLibrary() {
            viewModelScope.launch { runCatching { _library.value = repo.library() } }
        }

        /** Adds a built-in plan and opens it, where it can be backtested and put in a slot. */
        fun addFromLibrary(id: String) =
            act("Plan added. Backtest it or put it in a slot from its page.") {
                val r = repo.addLibraryPlan(id)
                _library.value = repo.library()
                _imported.value = r.strategy.id
            }

        fun stop(strategyId: String) =
            act("Strategy stopped") {
                repo.deactivate(strategyId)
                _slots.value = repo.slots()
            }

        private var lastResume: String? = null

        /** Restarts a paused strategy as it last ran (D-072); autonomous mode asks for the device lock. */
        fun resume(strategyId: String) {
            lastResume = strategyId
            act("Running again") {
                repo.resume(strategyId)
                _slots.value = repo.slots()
            }
        }

        /** After the device-lock prompt succeeds, records it and resumes the strategy that asked for it. */
        fun resumeAfterUnlock() {
            val id = lastResume ?: return
            viewModelScope.launch {
                runCatching { repo.reauthenticate("", null) }
                clearAction()
                resume(id)
            }
        }

        private val _imported = MutableStateFlow<String?>(null)
        val imported: StateFlow<String?> = _imported.asStateFlow()

        /** The screen opened the added strategy; returning to the list must not open it again. */
        fun importedOpened() {
            _imported.value = null
        }

        private val _instructions = MutableStateFlow<String?>(null)

        /** Instructions for the owner's own AI, ready to copy (D-041). */
        val instructions: StateFlow<String?> = _instructions.asStateFlow()

        fun loadInstructions(assetClass: String) =
            act("Instructions copied. Paste them into your AI chat, add your research, then paste its reply below.") {
                _instructions.value = repo.authoringPrompt(assetClass)
            }

        fun instructionsCopied() {
            _instructions.value = null
        }

        fun import(json: String) =
            act("Strategy imported") {
                val r = repo.importStrategy(json)
                _imported.value = r.strategy.id
            }
    }

/** One symbol's price chart with simulated trades on it (D-038); refreshes while open. */
@HiltViewModel
class ChartViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ViewModel() {
        val symbol: String = checkNotNull(saved["symbol"])
        private val portfolioId: String? = saved["portfolioId"]
        private val strategyId: String? = saved["strategyId"]
        private val _timeframe = MutableStateFlow(saved.get<String>("timeframe") ?: "1h")
        val timeframe: StateFlow<String> = _timeframe.asStateFlow()
        private val _chart = MutableStateFlow<CandleChart?>(null)
        val chart: StateFlow<CandleChart?> = _chart.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        init {
            viewModelScope.launch {
                while (true) {
                    load()
                    kotlinx.coroutines.delay(REFRESH_MS)
                }
            }
        }

        fun setTimeframe(tf: String) {
            _timeframe.value = tf
            _chart.value = null
            viewModelScope.launch { load() }
        }

        private suspend fun load() {
            val tf = _timeframe.value
            runCatching { repo.candles(symbol, tf, BARS, portfolioId, strategyId) }
                .onSuccess {
                    if (_timeframe.value == tf) {
                        _chart.value = it
                        _error.value = null
                    }
                }.onFailure { _error.value = it.message ?: "Price data is unavailable" }
        }

        companion object {
            const val BARS = 120
            private const val REFRESH_MS = 20_000L
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
        private val _download = MutableStateFlow<app.strategyforge.android.core.model.HistoryDownload?>(null)

        /** Stock history downloading for a waiting backtest (D-081), or null. */
        val download: StateFlow<app.strategyforge.android.core.model.HistoryDownload?> = _download.asStateFlow()

        private val _backtests = MutableStateFlow<List<Backtest>>(emptyList())
        val backtests: StateFlow<List<Backtest>> = _backtests.asStateFlow()
        private val _disclosure = MutableStateFlow<Disclosure?>(null)
        val disclosure: StateFlow<Disclosure?> = _disclosure.asStateFlow()
        private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
        val portfolios: StateFlow<List<Portfolio>> = _portfolios.asStateFlow()
        private val _slots = MutableStateFlow<List<Slot>>(emptyList())

        /** Every crypto and stock slot (D-051), to choose where this strategy runs. */
        val slots: StateFlow<List<Slot>> = _slots.asStateFlow()
        private val _price = MutableStateFlow<CandleChart?>(null)

        /** The strategy's price chart with its own trades marked (D-038). */
        val price: StateFlow<CandleChart?> = _price.asStateFlow()
        private val _priceSymbol = MutableStateFlow<String?>(null)
        val priceSymbol: StateFlow<String?> = _priceSymbol.asStateFlow()
        private val _priceTimeframe = MutableStateFlow("1h")
        val priceTimeframe: StateFlow<String> = _priceTimeframe.asStateFlow()
        private val _priceError = MutableStateFlow<String?>(null)
        val priceError: StateFlow<String?> = _priceError.asStateFlow()

        fun initPrice(
            symbol: String,
            timeframe: String,
        ) {
            if (_priceSymbol.value == null) loadPrice(symbol, timeframe)
        }

        fun loadPrice(
            symbol: String,
            timeframe: String,
        ) {
            _priceSymbol.value = symbol
            _priceTimeframe.value = timeframe
            _price.value = null
            _priceError.value = null
            viewModelScope.launch {
                runCatching { repo.candles(symbol, timeframe, 90, strategyId = id) }
                    .onSuccess { if (_priceSymbol.value == symbol && _priceTimeframe.value == timeframe) _price.value = it }
                    .onFailure { _priceError.value = it.message ?: "Price data is unavailable" }
            }
        }

        private val _scorecard = MutableStateFlow<Scorecard?>(null)

        /** This strategy's paper and backtest results (D-037). */
        val scorecard: StateFlow<Scorecard?> = _scorecard.asStateFlow()

        override fun source() = repo.strategy(id)

        init {
            refresh()
            loadExtras()
        }

        /** Re-reads the download progress and, once it is done, the backtests and the plan's status. */
        fun refreshDownload() {
            viewModelScope.launch {
                val before = _download.value
                val now = runCatching { repo.historyDownload(id) }.getOrNull()?.takeIf { it.left > 0 }
                _download.value = now
                if (before != null && now == null) {
                    runCatching { _backtests.value = repo.backtests(id) }
                    refresh()
                }
            }
        }

        fun loadExtras() {
            viewModelScope.launch {
                runCatching { _download.value = repo.historyDownload(id).takeIf { it.left > 0 } }
                runCatching { _backtests.value = repo.backtests(id) }
                runCatching { _scorecard.value = repo.scorecard(id) }
                runCatching { _disclosure.value = repo.disclosure() }
                runCatching { _slots.value = repo.slots() }
                runCatching { _outside.value = repo.outsidePositions(id) }
                repo.portfolios().collect { r -> if (r is Resource.Data) _portfolios.value = r.value.filter { it.status == "ACTIVE" } }
            }
        }

        private val _outside = MutableStateFlow<List<app.strategyforge.android.core.model.SlotHolding>>(emptyList())

        /** Positions in this plan's symbols and portfolio that it does not manage (D-075). */
        val outside: StateFlow<List<app.strategyforge.android.core.model.SlotHolding>> = _outside.asStateFlow()

        /** Lets the plan manage those positions as its own. */
        fun adopt() =
            act("The plan now manages these positions") {
                repo.adoptPositions(id)
                _outside.value = repo.outsidePositions(id)
            }

        fun revalidate() = act("Validation re-run") { repo.revalidate(id) }

        /** Saves the plan's conflict and capital policies (D-045); the new version needs a fresh backtest. */
        fun setPlanRules(
            conflictPolicy: String,
            capitalPolicy: String,
            maximumOpenRiskPercent: String?,
        ) = act("Plan rules saved as a new version. Run a backtest before trading it.") {
            repo.setPlanRules(id, conflictPolicy, capitalPolicy, maximumOpenRiskPercent)
            runCatching { _scorecard.value = repo.scorecard(id) }
        }

        fun backtest(
            from: String,
            to: String,
            capital: String,
        ) = act("Backtest started; results appear when it completes") {
            repo.runBacktest(id, from, to, capital)
            _backtests.value = repo.backtests(id)
            _download.value = runCatching { repo.historyDownload(id) }.getOrNull()?.takeIf { it.left > 0 }
        }

        private val _slotConflict = MutableStateFlow<SlotConflict?>(null)

        /** Set when another strategy of the same asset class is running (D-035): ask keep or close. */
        val slotConflict: StateFlow<SlotConflict?> = _slotConflict.asStateFlow()

        /** Starts the strategy in [slot]; it trades the slot's own portfolio, made with [startingCash] when the slot has none (D-079). */
        fun activate(
            portfolioId: String?,
            allocation: String,
            autonomous: Boolean,
            disclosureAccepted: Boolean,
            positions: String? = null,
            slot: Int? = null,
            startingCash: String? = null,
            shorting: Boolean = false,
        ): Unit =
            act(if (autonomous) "Autonomous paper trading enabled" else "Notifications mode enabled") {
                try {
                    val a =
                        repo.activate(
                            id,
                            portfolioId,
                            allocation,
                            autonomous,
                            if (autonomous && disclosureAccepted) _disclosure.value?.version else null,
                            positions,
                            slot,
                            startingCash,
                            shorting,
                        )
                    _replaced.value = a.replacedStrategyName?.let { ReplaceOutcome(it, a) }
                    runCatching { _slots.value = repo.slots() }
                } catch (e: ApiError.Http) {
                    if (e.code != "slot-occupied") throw e
                    _slotConflict.value = SlotConflict.from(e) { keep -> activate(portfolioId, allocation, autonomous, disclosureAccepted, if (keep) "KEEP" else "CLOSE", slot, startingCash, shorting) }
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
                        "30m" -> 90L
                        "1h", "4h" -> 180L
                        else -> 730L
                    }
                repo.runBacktest(id, end.minus(java.time.Duration.ofDays(days)).toString(), end.toString(), "100000")
                _backtests.value = repo.backtests(id)
                _download.value = runCatching { repo.historyDownload(id) }.getOrNull()?.takeIf { it.left > 0 }
            }

        fun deactivate() = act("Strategy stopped") { repo.deactivate(id) }

        /** Restarts the paused strategy as it last ran (D-072); autonomous mode asks for the device lock. */
        fun resume() =
            act("Running again") {
                repo.resume(id)
                runCatching { _slots.value = repo.slots() }
            }
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

        /** A running plan to open first (from Home), by its key. */
        private var requestedPlan: String? = saved["plan"]
        private val _overview = MutableStateFlow(requested == null && requestedPlan == null)

        /** True while the overview of every portfolio is shown rather than one portfolio (D-079). */
        val overview: StateFlow<Boolean> = _overview.asStateFlow()
        private val _summaries = MutableStateFlow<Map<String, PortfolioSummary>>(emptyMap())

        /** Each active portfolio's summary, for the overview's values. */
        val summaries: StateFlow<Map<String, PortfolioSummary>> = _summaries.asStateFlow()
        private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
        val portfolios: StateFlow<List<Portfolio>> = _portfolios.asStateFlow()
        private val _selected = MutableStateFlow(requested)
        val selected: StateFlow<String?> = _selected.asStateFlow()
        private val _orders = MutableStateFlow<Resource<Page<Order>>>(Resource.Loading)
        val orders: StateFlow<Resource<Page<Order>>> = _orders.asStateFlow()
        private val _range = MutableStateFlow("1W")
        val range: StateFlow<String> = _range.asStateFlow()
        private val _equity = MutableStateFlow<EquityChart?>(null)

        /** The selected portfolio's equity curve for [range] (D-038). */
        val equity: StateFlow<EquityChart?> = _equity.asStateFlow()

        private val _plans = MutableStateFlow<List<RunningPlan>>(emptyList())

        /** The running strategy plans, listed in the Portfolio menu like portfolios (D-058). */
        val plans: StateFlow<List<RunningPlan>> = _plans.asStateFlow()
        private val _plan = MutableStateFlow<String?>(null)

        /** The selected running plan's key, or null when a paper portfolio is shown. */
        val plan: StateFlow<String?> = _plan.asStateFlow()
        private val _scorecard = MutableStateFlow<Scorecard?>(null)

        /** The selected slot plan's results (closed trades, realized profit/loss). */
        val scorecard: StateFlow<Scorecard?> = _scorecard.asStateFlow()
        private val _tsxRun = MutableStateFlow<app.strategyforge.android.core.model.TsxRun?>(null)

        /** The selected TSX plan with its holdings, values and activity. */
        val tsxRun: StateFlow<app.strategyforge.android.core.model.TsxRun?> = _tsxRun.asStateFlow()

        override fun source(): Flow<Resource<PortfolioSummary>> {
            val id = _selected.value
            return if (id == null) kotlinx.coroutines.flow.flowOf(Resource.Loading) else repo.portfolioSummary(id)
        }

        init {
            viewModelScope.launch {
                repo.portfolios().collect { r ->
                    if (r is Resource.Data) {
                        _portfolios.value = r.value
                        if (_overview.value) loadSummaries()
                        if (_selected.value == null) {
                            _selected.value = r.value.firstOrNull { it.status == "ACTIVE" }?.id
                            reload()
                        }
                    }
                }
            }
            reload()
            loadPlans()
        }

        fun select(id: String) {
            _overview.value = false
            _plan.value = null
            _selected.value = id
            reload()
        }

        /** Opens an overview card: its running plan, else its portfolio (D-079). */
        fun open(e: PortfolioEntry) {
            val key = e.planKey
            val id = e.portfolioId
            if (key != null) {
                _overview.value = false
                selectPlan(key)
            } else if (id != null) {
                select(id)
            }
        }

        /** Back to the overview, with fresh values. */
        fun showOverview() {
            _overview.value = true
            loadPlans()
        }

        private fun loadSummaries() {
            val ids = _portfolios.value.filter { it.status == "ACTIVE" }.map { it.id }
            viewModelScope.launch {
                _summaries.value = ids.mapNotNull { id -> runCatching { id to repo.portfolioSummaryNow(id) }.getOrNull() }.toMap()
            }
        }

        /** Re-reads the running plans: strategies in crypto and stock slots, and active TSX plans. */
        fun loadPlans() {
            viewModelScope.launch {
                val slots =
                    runCatching { repo.slots() }
                        .getOrNull()
                        .orEmpty()
                        .filter { it.strategy != null && it.activation != null }
                        .map { RunningPlan.SlotPlan(it) }
                val tsx =
                    runCatching { repo.tsxRuns() }
                        .getOrNull()
                        .orEmpty()
                        .filter { it.status == "ACTIVE" }
                        .map { RunningPlan.TsxPlan(it) }
                _plans.value = slots + tsx
                requestedPlan?.let { key ->
                    requestedPlan = null
                    selectPlan(key)
                }
                if (_overview.value) loadSummaries()
                // A selected plan that stopped falls back to its portfolio (or the default view).
                if (_plan.value != null && _plans.value.none { it.key == _plan.value }) _plan.value = null
            }
        }

        fun selectPlan(key: String) {
            val p = _plans.value.firstOrNull { it.key == key } ?: return
            _plan.value = key
            when (p) {
                is RunningPlan.SlotPlan -> {
                    _scorecard.value = null
                    _selected.value = p.portfolioId
                    reload()
                    loadScorecard(p.strategyId)
                }
                is RunningPlan.TsxPlan -> {
                    _tsxRun.value = null
                    loadTsx(p.run.id)
                }
            }
        }

        private fun loadScorecard(strategyId: String) {
            viewModelScope.launch { runCatching { repo.scorecard(strategyId) }.onSuccess { if ((selectedPlan() as? RunningPlan.SlotPlan)?.strategyId == strategyId) _scorecard.value = it } }
        }

        private fun loadTsx(id: String) {
            viewModelScope.launch { runCatching { repo.tsxRun(id) }.onSuccess { if ((selectedPlan() as? RunningPlan.TsxPlan)?.run?.id == id) _tsxRun.value = it } }
        }

        private fun selectedPlan(): RunningPlan? = _plans.value.firstOrNull { it.key == _plan.value }

        /** Called while the screen is open: live values move with streamed prices (D-056). */
        fun refreshLive() {
            when (val p = selectedPlan()) {
                is RunningPlan.TsxPlan -> loadTsx(p.run.id)
                is RunningPlan.SlotPlan -> {
                    refresh()
                    loadScorecard(p.strategyId)
                }
                null -> refresh()
            }
        }

        /** Approve, decline or stop the selected TSX plan. */
        fun tsxAction(what: String) {
            val p = selectedPlan() as? RunningPlan.TsxPlan ?: return
            act(
                when (what) {
                    "approve" -> "Rebalance done at the latest closes"
                    "decline" -> "Rebalance declined"
                    "autonomous" -> "Now autonomous: rebalances are applied on schedule"
                    "notify" -> "Now notify and approve: you approve each rebalance"
                    else -> "Plan stopped"
                },
            ) {
                _tsxRun.value = repo.tsxRunAction(p.run.id, what)
                if (what == "stop") loadPlans()
            }
        }

        fun setRange(r: String) {
            _range.value = r
            loadEquity()
        }

        private fun loadEquity() {
            val id = _selected.value ?: return
            val r = _range.value
            viewModelScope.launch { runCatching { repo.equityChart(id, r) }.onSuccess { if (_selected.value == id && _range.value == r) _equity.value = it } }
        }

        fun reload() {
            refresh()
            val id = _selected.value ?: return
            viewModelScope.launch { repo.orders(id).collect { _orders.value = it } }
            loadEquity()
        }

        fun setShorting(
            id: String,
            enabled: Boolean,
        ) = act(if (enabled) "Simulated short selling turned on" else "Simulated short selling turned off") {
            repo.setShorting(id, enabled)
            _portfolios.value = _portfolios.value.map { if (it.id == id) it.copy(shortingEnabled = enabled) else it }
        }

        fun createPortfolio(
            name: String,
            balance: String,
        ) = act("Portfolio created") {
            val p = repo.createPortfolio(name, balance)
            _portfolios.value = _portfolios.value + p
            select(p.id)
        }

        private val _suggestions = MutableStateFlow<List<app.strategyforge.android.core.model.InstrumentHit>>(emptyList())

        /** Symbols matching the order form's symbol field (D-065). */
        val suggestions: StateFlow<List<app.strategyforge.android.core.model.InstrumentHit>> = _suggestions.asStateFlow()
        private var searchJob: kotlinx.coroutines.Job? = null

        /** Searches as the owner types, a moment after the last key so each letter is not a search. */
        fun searchSymbols(text: String) {
            searchJob?.cancel()
            if (text.isBlank()) {
                _suggestions.value = emptyList()
                return
            }
            searchJob =
                viewModelScope.launch {
                    kotlinx.coroutines.delay(SEARCH_DELAY_MS)
                    runCatching { repo.searchInstruments(text.trim()) }.onSuccess { _suggestions.value = it }
                }
        }

        fun clearSuggestions() {
            searchJob?.cancel()
            _suggestions.value = emptyList()
        }

        /**
         * Places a paper order (D-065): a US stock picked from search is added first, a symbol the app
         * cannot trade fails at the symbol field, and a rejection by the risk checks is shown at the
         * submit button with its reason rather than reported as submitted.
         */
        fun placeOrder(d: OrderDraft) =
            act("Paper order placed: ${d.side.lowercase().replace('_', ' ')} " + (d.amount?.let { "$$it of" } ?: d.quantity) + " ${d.symbol.uppercase()}") {
                val instrument = repo.addInstrument(d.symbol)
                val order = repo.placeOrder(d.copy(symbol = instrument.symbol))
                if (order.status == "REJECTED") {
                    throw app.strategyforge.android.core.state
                        .FieldError("submit", "Order rejected: ${order.rejectionReason ?: "the risk checks did not allow it"}")
                }
                clearSuggestions()
            }

        fun cancel(orderId: String) = act("Order cancelled") { repo.cancelOrder(orderId) }
    }

/** Pause after the last key before the symbol field searches (D-065). */
const val SEARCH_DELAY_MS = 250L

/** A running strategy plan as the Portfolio menu lists it (D-058). */
sealed interface RunningPlan {
    val key: String

    /** Short chip label, for example "Crypto 1 · Chart Champions v3". */
    val label: String

    data class SlotPlan(
        val slot: Slot,
    ) : RunningPlan {
        override val key get() = "slot:${slot.assetClass}:${slot.number}"
        override val label get() = (if (slot.assetClass == "CRYPTO") "Crypto " else "Stock ") + "${slot.number} · ${slot.strategy?.name ?: ""}"
        val strategyId: String get() = slot.strategy?.id ?: ""
        val portfolioId: String get() = slot.activation?.portfolioId ?: ""

        /** Symbols the plan holds in its portfolio. */
        val symbols: Set<String> get() =
            slot.holdings
                .filter { it.portfolioId == null || it.portfolioId == portfolioId }
                .map { it.symbol }
                .toSet()
    }

    data class TsxPlan(
        val run: app.strategyforge.android.core.model.TsxRun,
    ) : RunningPlan {
        override val key get() = "tsx:${run.id}"
        override val label get() = "TSX ${run.slot} · ${run.planName}"
    }
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

        private val _limit = MutableStateFlow<Int?>(null)

        /** The longest message the AI budget allows (D-040). */
        val limit: StateFlow<Int?> = _limit.asStateFlow()

        init {
            refresh()
            viewModelScope.launch { runCatching { _limit.value = repo.researchMessageLimit() } }
        }

        fun start(
            message: String,
            assetClass: String,
        ) = act("Research started") {
            _started.value = repo.startConversation(message.trim(), assetClass).session.id
        }

        /** Research done elsewhere, pasted in whole (D-041). */
        fun importResearch(
            text: String,
            assetClass: String,
        ) = act("Research imported; the AI is turning it into a strategy") {
            _started.value = repo.importResearch(text.trim(), assetClass).session.id
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
        private val _limit = MutableStateFlow<Int?>(null)

        /** The longest message the AI budget allows (D-040). */
        val limit: StateFlow<Int?> = _limit.asStateFlow()

        init {
            load()
            viewModelScope.launch { runCatching { _limit.value = repo.researchMessageLimit() } }
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
