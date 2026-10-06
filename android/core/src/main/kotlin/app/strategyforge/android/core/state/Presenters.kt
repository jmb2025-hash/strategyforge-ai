package app.strategyforge.android.core.state

import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.model.BootstrapStatus
import app.strategyforge.android.core.model.DecisionResult
import app.strategyforge.android.core.model.EmergencyState
import app.strategyforge.android.core.model.RecommendationDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID

/** Outcome of a user-initiated action, shown as a banner or dialog. */
sealed interface ActionState {
    data object Idle : ActionState

    data object Running : ActionState

    data class Done(
        val message: String,
    ) : ActionState

    data class Failed(
        val message: String,
        /** The backend requires recent authentication; the UI asks for the password and retries. */
        val needsReauth: Boolean = false,
        val permissionDenied: Boolean = false,
        /** The form field the failure is about, so the screen can show it there and scroll to it (D-065). */
        val field: String? = null,
    ) : ActionState
}

fun Throwable.toFailure(): ActionState.Failed =
    ActionState.Failed(
        Repository.message(this),
        needsReauth = (this as? ApiError.Http)?.recentAuthRequired == true,
        permissionDenied = (this as? ApiError.Http)?.permissionDenied == true,
        field = (this as? ApiError.Http)?.field ?: (this as? FieldError)?.field,
    )

/** A failure a screen found before calling the engine, about one form field (D-065). */
class FieldError(
    val field: String,
    message: String,
) : IllegalArgumentException(message)

// ---------------------------------------------------------------------------- access

enum class AccessStep { SERVER, BOOTSTRAP, LOGIN, RECOVERY, RECOVERY_CODES, SIGNED_IN }

data class AccessState(
    val step: AccessStep = AccessStep.SERVER,
    val serverUrl: String = "",
    val bootstrapTokenRequired: Boolean = false,
    val recoveryCodes: List<String> = emptyList(),
    val notice: String? = null,
    val action: ActionState = ActionState.Idle,
)

/**
 * Splash/bootstrap/login/recovery flow. The first run creates the single owner and shows the
 * recovery codes exactly once; later runs sign in (optionally with TOTP) or recover.
 */
class AccessPresenter(
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val saveServer: (String) -> Unit,
    private val validateServer: (String) -> String?,
    initialServer: String?,
    signedIn: Boolean,
) {
    private val _state =
        MutableStateFlow(
            AccessState(
                step =
                    if (initialServer == null) {
                        AccessStep.SERVER
                    } else if (signedIn) {
                        AccessStep.SIGNED_IN
                    } else {
                        AccessStep.LOGIN
                    },
                serverUrl = initialServer.orEmpty(),
            ),
        )
    val state: StateFlow<AccessState> = _state.asStateFlow()

    fun connect(url: String) {
        val valid = validateServer(url)
        if (valid == null) {
            _state.update { it.copy(action = ActionState.Failed("Enter an https:// address for your backend")) }
            return
        }
        saveServer(valid)
        _state.update { it.copy(serverUrl = valid, action = ActionState.Running) }
        scope.launch {
            runCatching { repo.bootstrapStatus() }
                .onSuccess { s: BootstrapStatus ->
                    _state.update {
                        it.copy(step = if (s.bootstrapped) AccessStep.LOGIN else AccessStep.BOOTSTRAP, bootstrapTokenRequired = s.bootstrapTokenRequired, action = ActionState.Idle)
                    }
                }.onFailure { e -> _state.update { it.copy(action = e.toFailure()) } }
        }
    }

    fun bootstrap(
        username: String,
        password: String,
        confirm: String,
        deviceName: String,
        timezone: String,
        token: String?,
    ) {
        val problem =
            when {
                username.isBlank() -> "Choose a username"
                password.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters for the password"
                password != confirm -> "Passwords do not match"
                else -> null
            }
        if (problem != null) {
            _state.update { it.copy(action = ActionState.Failed(problem)) }
            return
        }
        run {
            val r = repo.bootstrap(username.trim(), password, deviceName, timezone, token?.takeIf { it.isNotBlank() })
            _state.update { it.copy(step = AccessStep.RECOVERY_CODES, recoveryCodes = r.recoveryCodes, notice = r.notice, action = ActionState.Idle) }
        }
    }

    fun login(
        username: String,
        password: String,
        totp: String?,
        deviceName: String,
    ) {
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(action = ActionState.Failed("Enter your username and password")) }
            return
        }
        run {
            repo.login(username.trim(), password, totp, deviceName)
            _state.update { it.copy(step = AccessStep.SIGNED_IN, action = ActionState.Idle) }
        }
    }

    fun showRecovery() = _state.update { it.copy(step = AccessStep.RECOVERY, action = ActionState.Idle) }

    fun showLogin() = _state.update { it.copy(step = AccessStep.LOGIN, action = ActionState.Idle) }

    fun recover(
        username: String,
        code: String,
        newPassword: String,
        deviceName: String,
    ) {
        if (newPassword.length < MIN_PASSWORD) {
            _state.update { it.copy(action = ActionState.Failed("Use at least $MIN_PASSWORD characters for the new password")) }
            return
        }
        run {
            val r = repo.recover(username.trim(), code.trim(), newPassword, deviceName)
            _state.update { it.copy(step = AccessStep.SIGNED_IN, notice = "${r.remainingRecoveryCodes} recovery code(s) remain. TOTP was disabled; set it up again in Security.", action = ActionState.Idle) }
        }
    }

    /** The owner confirmed they stored the recovery codes; they are never shown again. */
    fun recoveryCodesSaved() = _state.update { it.copy(step = AccessStep.SIGNED_IN, recoveryCodes = emptyList(), action = ActionState.Idle) }

    fun signedOut() = _state.update { it.copy(step = if (it.serverUrl.isBlank()) AccessStep.SERVER else AccessStep.LOGIN, action = ActionState.Idle) }

    fun changeServer() = _state.update { it.copy(step = AccessStep.SERVER, action = ActionState.Idle) }

    private fun run(block: suspend () -> Unit) {
        _state.update { it.copy(action = ActionState.Running) }
        scope.launch { runCatching { block() }.onFailure { e -> _state.update { it.copy(action = e.toFailure()) } } }
    }

    companion object {
        const val MIN_PASSWORD = 12
    }
}

// ---------------------------------------------------------------------------- recommendation

data class RecommendationState(
    val loading: Boolean = true,
    val detail: RecommendationDetail? = null,
    val quantity: String = "",
    val limitPrice: String = "",
    val validation: String? = null,
    val action: ActionState = ActionState.Idle,
    val result: DecisionResult? = null,
    val loadError: String? = null,
)

/**
 * Recommendation detail and decisions (FR-063..FR-065). Acceptance needs the single-use token the
 * detail endpoint issued to this session; the idempotency key is created once per decision, so a
 * double tap or a network retry can never create a second order. Only risk-reducing modifications
 * are offered: smaller quantity, or a more conservative limit price.
 */
class RecommendationPresenter(
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val id: String,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(RecommendationState())
    val state: StateFlow<RecommendationState> = _state.asStateFlow()
    private var acceptKey: String? = null

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        scope.launch {
            runCatching { repo.recommendation(id) }
                .onSuccess { d ->
                    acceptKey = null
                    _state.update {
                        it.copy(loading = false, detail = d, quantity = d.recommendation.quantity, limitPrice = d.recommendation.limitPrice.orEmpty(), validation = null)
                    }
                }.onFailure { e -> _state.update { it.copy(loading = false, loadError = Repository.message(e)) } }
        }
    }

    fun setQuantity(q: String) = _state.update { it.copy(quantity = q, validation = validate(it.copy(quantity = q))) }

    fun setLimitPrice(p: String) = _state.update { it.copy(limitPrice = p, validation = validate(it.copy(limitPrice = p))) }

    fun accept() {
        val s = _state.value
        if (s.action == ActionState.Running) return // duplicate tap
        val d = s.detail ?: return
        val token = d.actionToken?.token
        if (d.recommendation.status != "PENDING" || token == null) {
            _state.update { it.copy(action = ActionState.Failed("This recommendation is ${d.recommendation.status.lowercase()} and can no longer be accepted")) }
            return
        }
        validate(s)?.let { msg ->
            _state.update { it.copy(validation = msg) }
            return
        }
        val r = d.recommendation
        val qty = s.quantity.trim().takeIf { BigDecimal(it).compareTo(BigDecimal(r.quantity)) != 0 }
        val limit = s.limitPrice.trim().takeIf { it.isNotEmpty() && r.limitPrice != null && BigDecimal(it).compareTo(BigDecimal(r.limitPrice)) != 0 }
        val key = acceptKey ?: newKey().also { acceptKey = it }
        _state.update { it.copy(action = ActionState.Running) }
        scope.launch {
            runCatching { repo.accept(id, token, qty, limit, key) }
                .onSuccess { res -> _state.update { it.copy(result = res, action = ActionState.Done(res.detail), detail = d.copy(recommendation = res.recommendation, actionToken = null)) } }
                .onFailure { e ->
                    val code = (e as? ApiError.Http)?.code
                    val message =
                        when (code) {
                            "recommendation-expired" -> "This recommendation expired before it was accepted. No order was placed."
                            "price-deviation" -> "The market moved too far since the recommendation (${Repository.message(e)}). No order was placed."
                            "recommendation-not-pending" -> "This recommendation was already decided on another device."
                            "action-token-invalid", "action-token-required" -> "The confirmation expired. Reload the recommendation and try again."
                            else -> Repository.message(e)
                        }
                    // A definitive rejection ends this attempt; offline failures keep the key for a safe retry.
                    if (e !is ApiError.Offline) acceptKey = null
                    _state.update { it.copy(action = ActionState.Failed(message)) }
                    if (e is ApiError.Http) load()
                }
        }
    }

    fun decline(reason: String?) = decide { repo.decline(id, reason).let { "Declined" } }

    fun snooze(minutes: Int) = decide { repo.snooze(id, minutes).let { "Snoozed for $minutes minutes" } }

    fun pauseStrategy() = decide { repo.pauseFromRecommendation(id).let { "Strategy paused; the recommendation was declined" } }

    private fun decide(block: suspend () -> String) {
        if (_state.value.action == ActionState.Running) return
        _state.update { it.copy(action = ActionState.Running) }
        scope.launch {
            runCatching { block() }
                .onSuccess { msg ->
                    _state.update { it.copy(action = ActionState.Done(msg)) }
                    load()
                }.onFailure { e -> _state.update { it.copy(action = e.toFailure()) } }
        }
    }

    companion object {
        /** Returns a message when the proposed modification is not permitted. */
        fun validate(s: RecommendationState): String? {
            val r = s.detail?.recommendation ?: return null
            val q = s.quantity.trim().toBigDecimalOrNull() ?: return "Enter a quantity"
            if (q.signum() <= 0) return "Quantity must be greater than zero"
            if (q > BigDecimal(r.quantity)) return "Quantity can only be reduced (max ${BigDecimal(r.quantity).stripTrailingZeros().toPlainString()})"
            val original = r.limitPrice?.let(::BigDecimal) ?: return null
            if (s.limitPrice.isBlank()) return null
            val l = s.limitPrice.trim().toBigDecimalOrNull() ?: return "Enter a valid limit price"
            if (l.signum() <= 0) return "Limit price must be positive"
            val buys = r.side == "BUY" || r.side == "BUY_TO_COVER"
            if (buys && l > original) return "A buy limit can only be lowered (max ${original.stripTrailingZeros().toPlainString()})"
            if (!buys && l < original) return "A sell limit can only be raised (min ${original.stripTrailingZeros().toPlainString()})"
            return null
        }
    }
}

// ---------------------------------------------------------------------------- emergency

data class EmergencyUiState(
    val state: EmergencyState? = null,
    val confirmation: String = "",
    val action: ActionState = ActionState.Idle,
    val pendingReauth: (() -> Unit)? = null,
)

/**
 * Emergency controls (FR-075). Engaging is one tap; releasing a control or closing all simulated
 * positions may require recent authentication, after which the same action is retried.
 */
class EmergencyPresenter(
    private val scope: CoroutineScope,
    private val repo: Repository,
) {
    private val _state = MutableStateFlow(EmergencyUiState())
    val state: StateFlow<EmergencyUiState> = _state.asStateFlow()

    fun load() {
        scope.launch { runCatching { repo.emergency() }.onSuccess { s -> _state.update { it.copy(state = s) } }.onFailure { e -> _state.update { it.copy(action = e.toFailure()) } } }
    }

    fun setConfirmation(text: String) = _state.update { it.copy(confirmation = text) }

    fun pauseAll(enabled: Boolean) = act { repo.pauseAll(enabled).detail }

    fun preventNewPositions(enabled: Boolean) = act { repo.preventNewPositions(enabled).detail }

    fun cancelPendingOrders() = act { repo.cancelPendingOrders().detail }

    fun disableAutonomous() = act { repo.disableAutonomous().detail }

    fun closeAll() {
        if (_state.value.confirmation.trim() != CONFIRMATION) {
            _state.update { it.copy(action = ActionState.Failed("Type $CONFIRMATION to confirm")) }
            return
        }
        act { repo.closeAllSimulatedPositions(CONFIRMATION).detail }
    }

    /** Called after the owner re-entered the password; retries the action that needed it. */
    fun reauthenticated() {
        val retry = _state.value.pendingReauth
        _state.update { it.copy(pendingReauth = null, action = ActionState.Idle) }
        retry?.invoke()
    }

    private fun act(block: suspend () -> String) {
        if (_state.value.action == ActionState.Running) return
        _state.update { it.copy(action = ActionState.Running) }
        scope.launch {
            runCatching { block() }
                .onSuccess { msg ->
                    _state.update { it.copy(action = ActionState.Done(msg), confirmation = "") }
                    load()
                }.onFailure { e ->
                    val f = e.toFailure()
                    _state.update { it.copy(action = f, pendingReauth = if (f.needsReauth) ({ act(block) }) else null) }
                }
        }
    }

    companion object {
        const val CONFIRMATION = "CLOSE ALL SIMULATED POSITIONS"
    }
}
