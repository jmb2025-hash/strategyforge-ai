package app.strategyforge.android.core.data

import app.strategyforge.android.core.api.ApiClient
import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.api.TokenStore
import app.strategyforge.android.core.cache.CachedResource
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.model.Activation
import app.strategyforge.android.core.model.Backtest
import app.strategyforge.android.core.model.BootstrapResponse
import app.strategyforge.android.core.model.BootstrapStatus
import app.strategyforge.android.core.model.ClockView
import app.strategyforge.android.core.model.DecisionResult
import app.strategyforge.android.core.model.Device
import app.strategyforge.android.core.model.Diagnostics
import app.strategyforge.android.core.model.Disclosure
import app.strategyforge.android.core.model.EmergencyResult
import app.strategyforge.android.core.model.EmergencyState
import app.strategyforge.android.core.model.FxRate
import app.strategyforge.android.core.model.Me
import app.strategyforge.android.core.model.Notification
import app.strategyforge.android.core.model.Order
import app.strategyforge.android.core.model.OrderResult
import app.strategyforge.android.core.model.Page
import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Provider
import app.strategyforge.android.core.model.PushConfig
import app.strategyforge.android.core.model.Recommendation
import app.strategyforge.android.core.model.RecommendationDetail
import app.strategyforge.android.core.model.RecoverResponse
import app.strategyforge.android.core.model.ResearchDetail
import app.strategyforge.android.core.model.ResearchSession
import app.strategyforge.android.core.model.SessionResponse
import app.strategyforge.android.core.model.Settings
import app.strategyforge.android.core.model.Strategy
import app.strategyforge.android.core.model.StrategyDetail
import app.strategyforge.android.core.model.StrategyResult
import app.strategyforge.android.core.model.UnreadCount
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Everything the Home screen needs, fetched together and cached as one entry (NFR-005). */
@Serializable
data class Dashboard(
    val portfolios: List<Portfolio>,
    val primary: PortfolioSummary? = null,
    val emergency: EmergencyState,
    val unread: UnreadCount,
    val pendingRecommendations: Page<Recommendation>,
    val diagnostics: Diagnostics,
    val strategies: List<Strategy>,
    val clock: ClockView? = null,
)

data class OrderDraft(
    val portfolioId: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val quantity: String,
    val limitPrice: String? = null,
    val stopPrice: String? = null,
    val timeInForce: String = "DAY",
)

/**
 * Single access point to the backend. Reads that screens show offline go through the cache;
 * decisions (accept, emergency controls, activation) always go to the network and are never
 * answered from cache.
 */
class Repository(
    private val api: ApiClient,
    private val cache: CachedResource,
    private val tokens: TokenStore,
) {
    // ----------------------------------------------------------------- access

    suspend fun bootstrapStatus(): BootstrapStatus = api.get("/v1/bootstrap", BootstrapStatus.serializer())

    suspend fun bootstrap(
        username: String,
        password: String,
        deviceName: String,
        timezone: String,
        bootstrapToken: String?,
    ): BootstrapResponse {
        val body =
            buildJsonObject {
                put("username", username)
                put("password", password)
                put("deviceName", deviceName)
                put("timezone", timezone)
                bootstrapToken?.let { put("bootstrapToken", it) }
            }
        val r = api.decode(api.post("/v1/bootstrap", body).body, BootstrapResponse.serializer())
        tokens.save(r.session.accessToken)
        return r
    }

    suspend fun login(
        username: String,
        password: String,
        totp: String?,
        deviceName: String,
    ): SessionResponse {
        val body =
            buildJsonObject {
                put("username", username)
                put("password", password)
                put("deviceName", deviceName)
                totp?.takeIf { it.isNotBlank() }?.let { put("totpCode", it) }
            }
        val s = api.decode(api.post("/v1/auth/login", body).body, SessionResponse.serializer())
        tokens.save(s.accessToken)
        return s
    }

    suspend fun recover(
        username: String,
        recoveryCode: String,
        newPassword: String,
        deviceName: String,
    ): RecoverResponse {
        val body =
            buildJsonObject {
                put("username", username)
                put("recoveryCode", recoveryCode)
                put("newPassword", newPassword)
                put("deviceName", deviceName)
            }
        val r = api.decode(api.post("/v1/auth/recover", body).body, RecoverResponse.serializer())
        tokens.save(r.session.accessToken)
        return r
    }

    suspend fun reauthenticate(
        password: String,
        totp: String?,
    ) {
        api.post(
            "/v1/auth/reauthenticate",
            buildJsonObject {
                put("password", password)
                totp?.takeIf { it.isNotBlank() }?.let { put("totpCode", it) }
            },
        )
    }

    suspend fun logout() {
        try {
            api.post("/v1/auth/logout")
        } finally {
            tokens.save(null)
        }
    }

    fun signedIn(): Boolean = tokens.token() != null

    suspend fun me(): Me = api.get("/v1/auth/me", Me.serializer())

    // ----------------------------------------------------------------- home

    fun dashboard(): Flow<Resource<Dashboard>> = cached("dashboard", Dashboard.serializer()) { dashboardJson() }

    private suspend fun dashboardJson(): JsonElement {
        val portfolios = api.get("/v1/portfolios").body
        val primaryId =
            (portfolios as? JsonArray)
                ?.firstOrNull { (it as? JsonObject)?.get("status")?.let { s -> (s as? JsonPrimitive)?.content } == "ACTIVE" }
                ?.let { ((it as JsonObject)["id"] as JsonPrimitive).content }
        return buildJsonObject {
            put("portfolios", portfolios)
            put("primary", primaryId?.let { api.get("/v1/portfolios/$it/summary").body } ?: JsonNull)
            put("emergency", api.get("/v1/emergency").body)
            put("unread", api.get("/v1/notifications/unread-count").body)
            put("pendingRecommendations", api.get("/v1/recommendations?status=PENDING&limit=20").body)
            put("diagnostics", api.get("/v1/diagnostics").body)
            put("strategies", api.get("/v1/strategies").body)
            put("clock", runCatching { api.get("/v1/market-data/clock").body }.getOrDefault(JsonNull))
        }
    }

    fun diagnostics(): Flow<Resource<Diagnostics>> = cached("diagnostics", Diagnostics.serializer()) { api.get("/v1/diagnostics").body }

    // ----------------------------------------------------------------- portfolio

    fun portfolios(): Flow<Resource<List<Portfolio>>> = cached("portfolios", ListSerializer(Portfolio.serializer())) { api.get("/v1/portfolios").body }

    fun portfolioSummary(id: String): Flow<Resource<PortfolioSummary>> = cached("portfolio:$id", PortfolioSummary.serializer()) { api.get("/v1/portfolios/${seg(id)}/summary").body }

    fun orders(portfolioId: String): Flow<Resource<Page<Order>>> = cached("orders:$portfolioId", Page.serializer(Order.serializer())) { api.get("/v1/orders?portfolioId=${q(portfolioId)}&limit=100").body }

    suspend fun createPortfolio(
        name: String,
        startingBalance: String,
    ): Portfolio =
        api.decode(
            api
                .post(
                    "/v1/portfolios",
                    buildJsonObject {
                        put("name", name)
                        put("startingBalance", startingBalance)
                    },
                ).body,
            Portfolio.serializer(),
        )

    suspend fun placeOrder(d: OrderDraft): Order {
        val body =
            buildJsonObject {
                put("portfolioId", d.portfolioId)
                put("symbol", d.symbol)
                put("side", d.side)
                put("orderType", d.orderType)
                put("quantity", d.quantity)
                put("timeInForce", d.timeInForce)
                d.limitPrice?.let { put("limitPrice", it) }
                d.stopPrice?.let { put("stopPrice", it) }
            }
        return api.decode(api.post("/v1/orders", body).body, OrderResult.serializer()).order
    }

    suspend fun cancelOrder(id: String) {
        api.post("/v1/orders/${seg(id)}/cancel", buildJsonObject { put("reason", "Cancelled by owner") })
    }

    suspend fun usdCad(): FxRate? = runCatching { api.get("/v1/market-data/fx/USD/CAD", FxRate.serializer()) }.getOrNull()

    // ----------------------------------------------------------------- strategies

    fun strategies(): Flow<Resource<List<Strategy>>> = cached("strategies", ListSerializer(Strategy.serializer())) { api.get("/v1/strategies").body }

    fun strategy(id: String): Flow<Resource<StrategyDetail>> = cached("strategy:$id", StrategyDetail.serializer()) { api.get("/v1/strategies/${seg(id)}").body }

    suspend fun importStrategy(json: String): StrategyResult {
        val content = SfJson.parseToJsonElement(json)
        return api.decode(api.post("/v1/strategies", buildJsonObject { put("content", content) }).body, StrategyResult.serializer())
    }

    suspend fun revalidate(id: String): StrategyResult = api.decode(api.post("/v1/strategies/${seg(id)}/validate").body, StrategyResult.serializer())

    suspend fun backtests(strategyId: String): List<Backtest> = api.decode(api.get("/v1/backtests?strategyId=${q(strategyId)}").body, ListSerializer(Backtest.serializer()))

    suspend fun runBacktest(
        strategyId: String,
        from: String,
        to: String,
        startingCapital: String,
    ): Backtest =
        api.decode(
            api
                .post(
                    "/v1/backtests",
                    buildJsonObject {
                        put("strategyId", strategyId)
                        put("from", from)
                        put("to", to)
                        put("startingCapital", startingCapital)
                    },
                ).body,
            Backtest.serializer(),
        )

    suspend fun disclosure(): Disclosure = api.get("/v1/autonomy/disclosure", Disclosure.serializer())

    suspend fun activate(
        strategyId: String,
        portfolioId: String,
        allocationPercent: String,
        autonomous: Boolean,
        disclosureVersion: String?,
    ): Activation {
        val body =
            buildJsonObject {
                put("portfolioId", portfolioId)
                put("allocationPercent", allocationPercent)
                put("mode", if (autonomous) "AUTONOMOUS" else "RECOMMENDATION")
                if (autonomous) {
                    put("disclosureAccepted", disclosureVersion != null)
                    disclosureVersion?.let { put("disclosureVersion", it) }
                }
            }
        return api.decode(api.post("/v1/strategies/${seg(strategyId)}/activate", body).body, Activation.serializer())
    }

    suspend fun deactivate(strategyId: String) {
        api.post("/v1/strategies/${seg(strategyId)}/deactivate", buildJsonObject { put("reason", "Paused by owner") })
    }

    // ----------------------------------------------------------------- recommendations

    fun recommendations(status: String?): Flow<Resource<Page<Recommendation>>> =
        cached("recommendations:${status ?: "all"}", Page.serializer(Recommendation.serializer())) {
            api.get("/v1/recommendations?limit=100" + (status?.let { "&status=${q(it)}" } ?: "")).body
        }

    /** Always fresh from the network: the detail issues the single-use action token (FR-063). */
    suspend fun recommendation(id: String): RecommendationDetail = api.get("/v1/recommendations/${seg(id)}", RecommendationDetail.serializer())

    suspend fun accept(
        id: String,
        actionToken: String,
        quantity: String?,
        limitPrice: String?,
        idempotencyKey: String,
    ): DecisionResult {
        val body =
            buildJsonObject {
                put("actionToken", actionToken)
                if (quantity != null || limitPrice != null) {
                    put(
                        "modification",
                        buildJsonObject {
                            quantity?.let { put("quantity", it) }
                            limitPrice?.let { put("limitPrice", it) }
                        },
                    )
                }
            }
        return api.decode(api.post("/v1/recommendations/${seg(id)}/accept", body, idempotencyKey).body, DecisionResult.serializer())
    }

    suspend fun decline(
        id: String,
        reason: String?,
    ): Recommendation = api.decode(api.post("/v1/recommendations/${seg(id)}/decline", buildJsonObject { reason?.let { put("reason", it) } }).body, Recommendation.serializer())

    suspend fun snooze(
        id: String,
        minutes: Int,
    ): Recommendation = api.decode(api.post("/v1/recommendations/${seg(id)}/snooze", buildJsonObject { put("minutes", minutes) }).body, Recommendation.serializer())

    suspend fun pauseFromRecommendation(id: String): Recommendation = api.decode(api.post("/v1/recommendations/${seg(id)}/pause-strategy").body, Recommendation.serializer())

    // ----------------------------------------------------------------- inbox

    fun notifications(): Flow<Resource<Page<Notification>>> = cached("notifications", Page.serializer(Notification.serializer())) { api.get("/v1/notifications?limit=100").body }

    suspend fun notification(id: String): Notification = api.get("/v1/notifications/${seg(id)}", Notification.serializer())

    suspend fun markRead(id: String) {
        api.post("/v1/notifications/${seg(id)}/read")
    }

    suspend fun markAllRead() {
        api.post("/v1/notifications/read-all")
    }

    // ----------------------------------------------------------------- emergency (never cached)

    suspend fun emergency(): EmergencyState = api.get("/v1/emergency", EmergencyState.serializer())

    suspend fun pauseAll(enabled: Boolean): EmergencyResult = emergencyAction("pause-all", buildJsonObject { put("enabled", enabled) })

    suspend fun preventNewPositions(enabled: Boolean): EmergencyResult = emergencyAction("prevent-new-positions", buildJsonObject { put("enabled", enabled) })

    suspend fun cancelPendingOrders(): EmergencyResult = emergencyAction("cancel-pending-orders", buildJsonObject { put("reason", "Owner emergency control") })

    suspend fun disableAutonomous(): EmergencyResult = emergencyAction("disable-autonomous", buildJsonObject { put("reason", "Owner emergency control") })

    suspend fun closeAllSimulatedPositions(confirmation: String): EmergencyResult = emergencyAction("close-all-simulated-positions", buildJsonObject { put("confirmation", confirmation) })

    private suspend fun emergencyAction(
        path: String,
        body: JsonObject,
    ): EmergencyResult = api.decode(api.post("/v1/emergency/$path", body).body, EmergencyResult.serializer())

    // ----------------------------------------------------------------- settings, providers, research

    fun settings(): Flow<Resource<Settings>> = cached("settings", Settings.serializer()) { api.get("/v1/settings").body }

    suspend fun updateSettings(
        current: Settings,
        timezone: String,
        displayCurrency: String,
        showCad: Boolean,
        theme: String,
    ): Settings {
        val body =
            buildJsonObject {
                put("timezone", timezone)
                put("displayCurrency", displayCurrency)
                put("showCadEquivalent", showCad)
                put("theme", theme)
                put("notifications", current.notifications)
                put("privacy", current.privacy)
                put("portfolioDefaults", current.portfolioDefaults)
            }
        return api.decode(api.put("/v1/settings", body, ifMatch = "\"${current.version}\"").body, Settings.serializer())
    }

    fun providers(): Flow<Resource<List<Provider>>> = cached("providers", ListSerializer(Provider.serializer())) { api.get("/v1/providers").body }

    suspend fun testProvider(id: String): Provider = api.decode(api.post("/v1/providers/${seg(id)}/test").body, Provider.serializer())

    fun research(): Flow<Resource<List<ResearchSession>>> = cached("research", ListSerializer(ResearchSession.serializer())) { api.get("/v1/research").body }

    suspend fun researchDetail(id: String): ResearchDetail = api.get("/v1/research/${seg(id)}", ResearchDetail.serializer())

    suspend fun createResearch(body: JsonObject): ResearchSession = api.decode(api.post("/v1/research", body).body, ResearchSession.serializer())

    suspend fun runResearch(id: String): ResearchDetail = api.decode(api.post("/v1/research/${seg(id)}/run").body, ResearchDetail.serializer())

    suspend fun reviewResearch(
        id: String,
        approve: Boolean,
        note: String?,
    ): ResearchDetail =
        api.decode(
            api
                .post(
                    "/v1/research/${seg(id)}/review",
                    buildJsonObject {
                        put("decision", if (approve) "APPROVE" else "REJECT")
                        note?.let { put("note", it) }
                    },
                ).body,
            ResearchDetail.serializer(),
        )

    suspend fun compileResearch(id: String): ResearchDetail = api.decode(api.post("/v1/research/${seg(id)}/compile").body, ResearchDetail.serializer())

    // ----------------------------------------------------------------- devices and push

    suspend fun pushConfig(): PushConfig = api.get("/v1/devices/push-config", PushConfig.serializer())

    suspend fun registerDevice(name: String): Device =
        api.decode(
            api
                .post(
                    "/v1/devices",
                    buildJsonObject {
                        put("name", name)
                        put("platform", "ANDROID")
                    },
                ).body,
            Device.serializer(),
        )

    suspend fun updatePushToken(
        deviceId: String,
        token: String?,
    ) {
        api.put(
            "/v1/devices/${seg(deviceId)}/push",
            buildJsonObject {
                put("pushToken", token?.let { JsonPrimitive(it) } ?: JsonNull)
                put("pushEnabled", token != null)
            },
        )
    }

    // ----------------------------------------------------------------- helpers

    suspend fun clearCache() = cache.clear()

    private fun <T> cached(
        key: String,
        serializer: KSerializer<T>,
        fetch: suspend () -> JsonElement,
    ): Flow<Resource<T>> = cache.load(key, serializer, fetch)

    private fun seg(s: String): String {
        require(s.matches(SAFE_SEGMENT)) { "Invalid identifier" }
        return s
    }

    private fun q(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    companion object {
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9-]{1,64}$")

        /** Maps an error to owner-facing text; backend problem details are already user-safe. */
        fun message(e: Throwable): String =
            when (e) {
                is ApiError.Http -> e.detail ?: e.title ?: "Request failed (HTTP ${e.status})"
                is ApiError -> e.message ?: "Request failed"
                is IllegalArgumentException -> e.message ?: "Invalid input"
                else -> "Unexpected error"
            }
    }
}
