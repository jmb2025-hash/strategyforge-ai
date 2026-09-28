package app.strategyforge.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.model.AiBudget
import app.strategyforge.android.core.model.BackupFile
import app.strategyforge.android.core.model.BackupVerification
import app.strategyforge.android.core.model.Device
import app.strategyforge.android.core.model.Me
import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.model.SessionInfo
import app.strategyforge.android.core.model.TotpSetup
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.BudgetForm
import app.strategyforge.android.core.state.ExportDataset
import app.strategyforge.android.core.state.ExportFormat
import app.strategyforge.android.core.state.ExportRequest
import app.strategyforge.android.core.state.TotpCode
import app.strategyforge.android.core.state.toFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Base for owner-account screens. These views are never cached on the device: sessions, devices,
 * budgets and backups are always read from the backend. An action blocked by the backend's
 * recent-authentication rule is remembered and re-run once the owner confirms the password.
 */
abstract class AccountViewModel : ViewModel() {
    private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
    val action: StateFlow<ActionState> = _action.asStateFlow()
    private var pending: (() -> Unit)? = null

    abstract fun load()

    protected fun act(
        success: String,
        block: suspend () -> Unit,
    ) {
        if (_action.value == ActionState.Running) return
        val run = { act(success, block) }
        _action.value = ActionState.Running
        viewModelScope.launch {
            _action.value =
                runCatching { block() }.fold(
                    {
                        pending = null
                        ActionState.Done(success)
                    },
                    { e ->
                        e.toFailure().also { if (it.needsReauth) pending = run }
                    },
                )
            load()
        }
    }

    /** Called after successful re-authentication: repeats the action the backend refused. */
    fun retryAfterReauth() {
        val p = pending
        pending = null
        _action.value = ActionState.Idle
        p?.invoke()
    }

    protected fun fail(message: String) {
        _action.value = ActionState.Failed(message)
    }

    fun clearAction() {
        _action.value = ActionState.Idle
    }
}

data class SecurityState(
    val me: Me? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val devices: List<Device> = emptyList(),
    val totpSetup: TotpSetup? = null,
    val recoveryCodes: List<String>? = null,
    val loading: Boolean = true,
    val loadError: String? = null,
)

/** FR-002: two-factor authentication, recovery codes, password, sessions and devices. */
@HiltViewModel
class SecurityViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : AccountViewModel() {
        private val _state = MutableStateFlow(SecurityState())
        val state: StateFlow<SecurityState> = _state.asStateFlow()

        init {
            load()
        }

        override fun load() {
            viewModelScope.launch {
                runCatching { Triple(repo.me(), repo.sessions(), repo.devices()) }
                    .onSuccess { (me, sessions, devices) ->
                        _state.update { it.copy(me = me, sessions = sessions.filter { s -> s.revokedAt == null }, devices = devices.filter { d -> d.revokedAt == null }, loading = false, loadError = null) }
                    }.onFailure { e -> _state.update { it.copy(loading = false, loadError = Repository.message(e)) } }
            }
        }

        fun startTotpSetup() = act("Scan the key in your authenticator app, then enter a code to finish") { _state.update { it.copy(totpSetup = repo.beginTotpSetup()) } }

        fun confirmTotp(input: String) {
            val code = TotpCode.normalize(input) ?: return fail("Enter the 6-digit code from your authenticator app")
            act("Two-factor authentication is on") {
                repo.confirmTotp(code)
                _state.update { it.copy(totpSetup = null) }
            }
        }

        fun cancelTotpSetup() = _state.update { it.copy(totpSetup = null) }

        fun disableTotp(input: String) {
            val code = TotpCode.normalize(input) ?: return fail("Enter the 6-digit code from your authenticator app")
            act("Two-factor authentication is off") { repo.disableTotp(code) }
        }

        fun regenerateRecoveryCodes() =
            act("New recovery codes created; the old ones no longer work") {
                val codes = repo.regenerateRecoveryCodes().recoveryCodes
                _state.update { it.copy(recoveryCodes = codes) }
            }

        fun dismissRecoveryCodes() = _state.update { it.copy(recoveryCodes = null) }

        fun changePassword(
            current: String,
            new: String,
            confirm: String,
        ) {
            if (new != confirm) return fail("The new passwords do not match")
            if (new.length < MIN_PASSWORD) return fail("The new password must have at least $MIN_PASSWORD characters")
            act("Password changed") { repo.changePassword(current, new) }
        }

        fun revokeSession(id: String) = act("Session signed out") { repo.revokeSession(id) }

        fun revokeOtherSessions() = act("All other sessions signed out") { repo.revokeOtherSessions() }

        fun revokeDevice(id: String) = act("Device removed; it no longer receives notifications") { repo.revokeDevice(id) }

        companion object {
            const val MIN_PASSWORD = 12
        }
    }

/** FR-037: AI spending and request ceilings. Raising a ceiling requires recent authentication. */
@HiltViewModel
class BudgetViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : AccountViewModel() {
        private val _budget = MutableStateFlow<Resource<AiBudget>>(Resource.Loading)
        val budget: StateFlow<Resource<AiBudget>> = _budget.asStateFlow()

        init {
            load()
        }

        override fun load() {
            viewModelScope.launch {
                _budget.value =
                    runCatching { repo.aiBudget() }.fold(
                        { Resource.Data(it, java.time.Instant.now(), fromCache = false, stale = false, refreshing = false) },
                        {
                            Resource.Failure(
                                it as? app.strategyforge.android.core.api.ApiError ?: app.strategyforge.android.core.api.ApiError
                                    .Malformed(it),
                            )
                        },
                    )
            }
        }

        fun save(
            current: AiBudget,
            monthly: String,
            daily: String,
            maxOutput: String,
            maxInput: String,
        ) {
            val form = BudgetForm.parse(monthly, daily, maxOutput, maxInput).getOrElse { return fail(it.message ?: "Invalid budget") }
            act("AI budget saved") { repo.updateAiBudget(current, form) }
        }
    }

data class BackupsState(
    val items: List<BackupFile> = emptyList(),
    val verifications: Map<String, BackupVerification> = emptyMap(),
    val loading: Boolean = true,
    val loadError: String? = null,
)

/** FR-112: encrypted backups on the backend host. Restore stays an offline, host-side command. */
@HiltViewModel
class BackupsViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : AccountViewModel() {
        private val _state = MutableStateFlow(BackupsState())
        val state: StateFlow<BackupsState> = _state.asStateFlow()

        init {
            load()
        }

        override fun load() {
            viewModelScope.launch {
                runCatching { repo.backups() }
                    .onSuccess { items -> _state.update { it.copy(items = items, loading = false, loadError = null) } }
                    .onFailure { e -> _state.update { it.copy(loading = false, loadError = Repository.message(e)) } }
            }
        }

        fun create() = act("Encrypted backup created") { repo.createBackup() }

        fun verify(name: String) =
            act("Verification finished") {
                val v = repo.verifyBackup(name)
                _state.update { it.copy(verifications = it.verifications + (name to v)) }
            }
    }

data class ExportsState(
    val portfolios: List<Portfolio> = emptyList(),
    val dataset: ExportDataset = ExportDataset.LEDGER,
    val format: ExportFormat = ExportFormat.CSV,
    val portfolioId: String? = null,
    val lastReconciled: Boolean? = null,
)

/** FR-105: saves CSV/JSON exports to a location the owner picks (Storage Access Framework). */
@HiltViewModel
class ExportsViewModel
    @Inject
    constructor(
        private val app: Application,
        private val repo: Repository,
    ) : AccountViewModel() {
        private val _state = MutableStateFlow(ExportsState())
        val state: StateFlow<ExportsState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                repo.portfolios().collect { r ->
                    if (r is Resource.Data) {
                        _state.update { s -> s.copy(portfolios = r.value, portfolioId = s.portfolioId ?: r.value.firstOrNull { it.status == "ACTIVE" }?.id) }
                    }
                }
            }
        }

        /** Nothing to reload after an export: the portfolio list stays subscribed. */
        override fun load() = Unit

        fun select(
            dataset: ExportDataset? = null,
            format: ExportFormat? = null,
            portfolioId: String? = null,
        ) = _state.update { s -> s.copy(dataset = dataset ?: s.dataset, format = format ?: s.format, portfolioId = portfolioId ?: s.portfolioId) }

        private var pending: ExportRequest? = null

        /**
         * Validates the selection and remembers it (surviving recreation while the system "save as"
         * dialog is open). Returns the suggested file name, or null when the selection is invalid.
         */
        fun prepare(): ExportRequest? =
            _state.value
                .let { s -> ExportRequest.of(s.dataset, s.format, s.portfolioId) }
                .onSuccess { pending = it }
                .getOrElse {
                    fail(it.message ?: "Invalid export")
                    null
                }

        /** Result of the save dialog: null means the owner cancelled. */
        fun onDocumentChosen(target: Uri?) {
            val request = pending ?: return
            pending = null
            if (target != null) write(request, target)
        }

        private fun write(
            request: ExportRequest,
            target: Uri,
        ) = act("Export saved") {
            val d = repo.export(request)
            withContext(Dispatchers.IO) {
                val out = app.contentResolver.openOutputStream(target, "wt") ?: throw IllegalArgumentException("Cannot write to the chosen location")
                out.use { it.write(d.bytes) }
            }
            _state.update {
                it.copy(
                    lastReconciled =
                        d.headers.entries
                            .firstOrNull { e -> e.key.equals("X-StrategyForge-Reconciled", true) }
                            ?.value == "true",
                )
            }
        }
    }
