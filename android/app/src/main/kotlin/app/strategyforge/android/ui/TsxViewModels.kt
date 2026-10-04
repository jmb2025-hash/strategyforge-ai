package app.strategyforge.android.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.model.TsxBacktest
import app.strategyforge.android.core.model.TsxCatalog
import app.strategyforge.android.core.model.TsxDataStatus
import app.strategyforge.android.core.model.TsxRun
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.toFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The TSX plans screen (D-055): built-in plans with their research, the data status and the ten TSX slots. */
@HiltViewModel
class TsxPlansViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : ViewModel() {
        private val _catalog = MutableStateFlow<TsxCatalog?>(null)
        val catalog: StateFlow<TsxCatalog?> = _catalog.asStateFlow()
        private val _data = MutableStateFlow<TsxDataStatus?>(null)
        val data: StateFlow<TsxDataStatus?> = _data.asStateFlow()
        private val _runs = MutableStateFlow<List<TsxRun>>(emptyList())
        val runs: StateFlow<List<TsxRun>> = _runs.asStateFlow()
        private val _backtests = MutableStateFlow<Map<String, TsxBacktest>>(emptyMap())

        /** The latest backtest started from this screen, per plan. */
        val backtests: StateFlow<Map<String, TsxBacktest>> = _backtests.asStateFlow()
        private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
        val action: StateFlow<ActionState> = _action.asStateFlow()
        private val _started = MutableStateFlow<String?>(null)

        /** The run just started, so the screen can open it. */
        val started: StateFlow<String?> = _started.asStateFlow()
        private var poll: Job? = null

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                runCatching { repo.tsxCatalog() }.onSuccess { _catalog.value = it }.onFailure { _action.value = it.toFailure() }
                loadStatus()
            }
        }

        /** Re-reads the running plans (their values move with intraday prices). */
        fun loadRuns() {
            viewModelScope.launch { runCatching { repo.tsxRuns() }.onSuccess { _runs.value = it } }
        }

        private suspend fun loadStatus() {
            runCatching { repo.tsxData() }.onSuccess {
                _data.value = it
                if (it.refreshing) watchRefresh()
            }
            runCatching { repo.tsxRuns() }.onSuccess { _runs.value = it }
        }

        fun refreshData() =
            perform("Downloading TSX prices in the background. This takes a few minutes the first time.") {
                val r = repo.refreshTsxData()
                _data.value = r.status
                if (!r.started && !r.status.refreshing) error(r.status.error ?: "The download could not start")
                watchRefresh()
            }

        private fun watchRefresh() {
            if (poll?.isActive == true) return
            poll =
                viewModelScope.launch {
                    while (true) {
                        delay(3_000)
                        val s = runCatching { repo.tsxData() }.getOrNull() ?: continue
                        _data.value = s
                        if (!s.refreshing) break
                    }
                    runCatching { repo.tsxRuns() }.onSuccess { _runs.value = it }
                }
        }

        fun start(
            planId: String,
            slot: Int?,
            startingCash: String,
            drip: Boolean,
            autonomous: Boolean,
        ) = perform(if (autonomous) "Plan started. It bought its first holdings at the latest closes." else "Plan started. Approve its first holdings to invest.") {
            val r = repo.startTsxRun(planId, slot, startingCash, drip, autonomous)
            _runs.value = repo.tsxRuns()
            _started.value = r.id
        }

        fun consumeStarted() {
            _started.value = null
        }

        fun stop(id: String) =
            perform("Plan stopped") {
                repo.tsxRunAction(id, "stop")
                _runs.value = repo.tsxRuns()
            }

        fun backtest(
            planId: String,
            from: String?,
            startingCash: String,
        ) = perform("Backtest finished") {
            var b = repo.startTsxBacktest(planId, from, startingCash)
            _backtests.value = _backtests.value + (planId to b)
            while (b.status == "RUNNING") {
                delay(1_500)
                b = repo.tsxBacktest(b.id)
            }
            _backtests.value = _backtests.value + (planId to b)
            if (b.status != "COMPLETED") error(b.error ?: "The backtest failed")
        }

        private fun perform(
            success: String,
            block: suspend () -> Unit,
        ) {
            if (_action.value == ActionState.Running) return
            _action.value = ActionState.Running
            viewModelScope.launch {
                _action.value = runCatching { block() }.fold({ ActionState.Done(success) }, { it.toFailure() })
            }
        }
    }

/** One running (or stopped) TSX plan: holdings, the rebalance waiting for approval, values and dividends. */
@HiltViewModel
class TsxRunViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ViewModel() {
        private val id: String = checkNotNull(saved["id"])
        private val _run = MutableStateFlow<TsxRun?>(null)
        val run: StateFlow<TsxRun?> = _run.asStateFlow()
        private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
        val action: StateFlow<ActionState> = _action.asStateFlow()

        init {
            load()
        }

        fun load() {
            viewModelScope.launch { runCatching { repo.tsxRun(id) }.onSuccess { _run.value = it }.onFailure { _action.value = it.toFailure() } }
        }

        fun approve() = act("approve", "Rebalance done at the latest closes")

        fun decline() = act("decline", "Rebalance declined. The plan keeps its holdings until the next one.")

        fun stop() = act("stop", "Plan stopped")

        private fun act(
            what: String,
            success: String,
        ) {
            if (_action.value == ActionState.Running) return
            _action.value = ActionState.Running
            viewModelScope.launch {
                _action.value =
                    runCatching { _run.value = repo.tsxRunAction(id, what) }.fold({ ActionState.Done(success) }, { it.toFailure() })
            }
        }
    }
