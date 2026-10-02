package app.strategyforge.engine.api

import app.strategyforge.engine.Engine
import app.strategyforge.engine.autonomy.Activation
import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.autonomy.ActivationRequest
import app.strategyforge.engine.autonomy.AutonomyDisclosure
import app.strategyforge.engine.backtest.BacktestRequest
import app.strategyforge.engine.backtest.BacktestView
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.execution.OrderRequest
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.PaperOrder
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.market.MarketMode
import app.strategyforge.engine.notifications.NotificationView
import app.strategyforge.engine.operations.DiagnosticsReport
import app.strategyforge.engine.portfolio.Portfolio
import app.strategyforge.engine.portfolio.PortfolioCreate
import app.strategyforge.engine.portfolio.PortfolioSummary
import app.strategyforge.engine.portfolio.PositionView
import app.strategyforge.engine.reports.Attribution
import app.strategyforge.engine.reports.ExportService
import app.strategyforge.engine.reports.OutcomeGroup
import app.strategyforge.engine.research.AiBudget
import app.strategyforge.engine.research.AiBudgetUpdate
import app.strategyforge.engine.research.AiProviderType
import app.strategyforge.engine.research.AiProviderView
import app.strategyforge.engine.research.ResearchCreate
import app.strategyforge.engine.research.ResearchDetail
import app.strategyforge.engine.research.ResearchSession
import app.strategyforge.engine.research.ReviewRequest
import app.strategyforge.engine.risk.OrderSource
import app.strategyforge.engine.settings.OwnerSettings
import app.strategyforge.engine.settings.PortfolioDefaults
import app.strategyforge.engine.signals.AcceptRequest
import app.strategyforge.engine.signals.CloseAllRequest
import app.strategyforge.engine.signals.EmergencyResult
import app.strategyforge.engine.signals.EmergencyState
import app.strategyforge.engine.signals.Modification
import app.strategyforge.engine.signals.Recommendation
import app.strategyforge.engine.signals.RecommendationDetail
import app.strategyforge.engine.signals.ToggleRequest
import app.strategyforge.engine.strategy.StrategyResult
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.strategy.StrategyVersionView
import app.strategyforge.engine.strategy.StrategyView
import app.strategyforge.engine.strategy.ValidationView
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** A request as the app's API client sends it. */
data class LocalRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
    val ifMatch: String? = null,
)

/** A response in the Version 1 API's JSON shapes (or a file download for exports). */
class LocalResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(
            status: Int,
            element: JsonElement,
            headers: Map<String, String> = emptyMap(),
        ) = LocalResponse(status, element.toString().toByteArray(Charsets.UTF_8), "application/json", headers)
    }
}

/**
 * The Version 1 REST API, answered in-process by the on-device engine (D-027, D-031). The app's
 * screens, models, cache and API client are unchanged; only the transport moved from a server to
 * the phone. Every call must run on the engine thread (see [EngineHost]).
 *
 * Errors are returned as the same problem documents the server produced, so the UI's handling of
 * validation errors, conflicts and "confirm it's you" prompts keeps working.
 */
class LocalApi(
    private val engine: Engine,
    private val scheduler: app.strategyforge.engine.EngineScheduler? = null,
) {
    private val log = EngineLog.of(javaClass)

    private class Route(
        val method: String,
        pattern: String,
        val handler: (LocalRequest, List<String>) -> LocalResponse,
    ) {
        val regex = Regex("^" + pattern.replace("{id}", "([A-Za-z0-9._-]+)") + "$")
    }

    private val routes = mutableListOf<Route>()

    private fun get(
        pattern: String,
        h: (LocalRequest, List<String>) -> Any?,
    ) {
        routes += Route("GET", pattern) { r, g -> ok(h(r, g)) }
    }

    private fun post(
        pattern: String,
        status: Int = 200,
        h: (LocalRequest, List<String>) -> Any?,
    ) {
        routes += Route("POST", pattern) { r, g -> ok(h(r, g), status) }
    }

    private fun put(
        pattern: String,
        h: (LocalRequest, List<String>) -> Any?,
    ) {
        routes += Route("PUT", pattern) { r, g -> ok(h(r, g)) }
    }

    private fun ok(
        value: Any?,
        status: Int = 200,
    ): LocalResponse =
        when (value) {
            is LocalResponse -> value
            is JsonElement -> LocalResponse.json(status, value)
            else -> LocalResponse.json(status, value.toJsonElement())
        }

    fun handle(request: LocalRequest): LocalResponse {
        val candidates = routes.mapNotNull { r -> r.regex.matchEntire(request.path)?.let { r to it.groupValues.drop(1) } }
        val match = candidates.firstOrNull { it.first.method == request.method } ?: return problem(if (candidates.isEmpty()) 404 else 405, "not-found", "No such operation on this device")
        return try {
            match.first.handler(request, match.second)
        } catch (e: EngineException) {
            problem(e.status, e.code, e.message, e.properties)
        } catch (e: IllegalArgumentException) {
            problem(400, "invalid-request", e.message ?: "Invalid request")
        } catch (e: RuntimeException) {
            log.error("Local API {} {} failed", request.method, request.path, e)
            problem(500, "internal-error", "Something went wrong on this device (${e.javaClass.simpleName})")
        }
    }

    private fun problem(
        status: Int,
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ): LocalResponse = LocalResponse.json(status, (mapOf("status" to status, "code" to code, "title" to title(status), "detail" to detail) + props.mapValues { plain(it.value) }).toJsonElement())

    private fun title(status: Int) =
        when (status) {
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            412 -> "Precondition Failed"
            422 -> "Unprocessable Entity"
            503 -> "Service Unavailable"
            else -> "Error"
        }

    /** Problem properties may hold engine objects; keep them JSON-friendly. */
    private fun plain(v: Any?): Any? =
        when (v) {
            is app.strategyforge.engine.strategy.ValidationIssue -> mapOf("code" to v.code, "path" to v.path, "message" to v.message, "category" to v.category.name, "severity" to v.severity.name)
            is List<*> -> v.map { plain(it) }
            else -> v
        }

    // ------------------------------------------------------------------ request helpers

    private fun obj(r: LocalRequest): JsonObject = (r.body as? JsonObject) ?: JsonObject(emptyMap())

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.req(k: String): String = str(k)?.takeIf { it.isNotBlank() } ?: throw Problems.badRequest("missing-field", "$k is required")

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.dec(k: String): BigDecimal? = str(k)?.let { it.toBigDecimalOrNull() ?: throw Problems.badRequest("invalid-number", "$k must be a number") }

    private fun uuid(s: String): UUID = runCatching { UUID.fromString(s) }.getOrElse { throw Problems.notFound("Item", s) }

    private fun version(r: LocalRequest): Long? = r.ifMatch?.trim('"', ' ', 'W', '/')?.toLongOrNull()

    private fun limit(r: LocalRequest) = r.query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50

    private fun jackson(node: JsonNode?): JsonElement = node?.let { EngineJson.parseToJsonElement(JacksonCanonical.mapper.writeValueAsString(it)) } ?: JsonNull

    private fun <T> page(items: List<T>) = mapOf("items" to items, "nextCursor" to null)

    init {
        access()
        home()
        portfolios()
        strategies()
        recommendations()
        inbox()
        emergency()
        settingsAndProviders()
        research()
        reportsAndExports()
        backups()
    }

    // ------------------------------------------------------------------ access (the device lock replaces sign-in)

    private fun access() {
        get("/v1/bootstrap") { _, _ -> mapOf("bootstrapped" to true, "bootstrapTokenRequired" to false) }
        // The app calls this only after the owner passes the device-lock prompt (D-029); the
        // request never leaves the phone and nothing else can reach this in-process API.
        post("/v1/auth/reauthenticate") { _, _ ->
            engine.auth.confirmed()
            mapOf("confirmed" to true)
        }
        get("/v1/runtime") { _, _ -> runtime() }
        get("/v1/market-data/stocks") { _, _ -> stocks() }
        put("/v1/market-data/stocks/key") { r, _ ->
            engine.equityKey.set(obj(r).str("key"))
            stocks()
        }
        post("/v1/market-data/stocks/test") { _, _ ->
            if (!engine.equityKey.configured()) throw Problems.unprocessable("no-stock-key", "Add a Twelve Data key first")
            val t = engine.equitySource()?.diagnose(engine.wall.instant()) ?: throw Problems.unavailable("stocks-unavailable", "Stock data is not available in this build")
            stocks() + mapOf("lastTestStatus" to t.status, "lastTestDetail" to t.detail)
        }
        post("/v1/market-data/futures/test") { _, _ ->
            val (source, t) = engine.derivatives.diagnoseLive(engine.wall.instant())
            mapOf("source" to source, "status" to t.status, "detail" to t.detail)
        }
        put("/v1/runtime") { r, _ ->
            val b = obj(r)
            b.str("marketMode")?.let { engine.setMarketMode(enumOf<MarketMode>(it, "marketMode")) }
            b.int("demoStepMinutes")?.let { v ->
                val s = scheduler ?: throw Problems.unavailable("scheduler-unavailable", "The background engine is not running")
                if (v !in 0..60) throw Problems.badRequest("invalid-demo-speed", "Demo speed must be 0-60 replay minutes per tick")
                s.demoStepMinutes = v.toLong()
            }
            runtime()
        }
    }

    private fun stocks() =
        mapOf(
            "provider" to "TWELVE_DATA",
            "configured" to engine.equityKey.configured(),
            "fingerprint" to engine.equityKey.fingerprint(),
            "keyUrl" to "https://twelvedata.com/account/api-keys",
        )

    private fun runtime() =
        mapOf(
            "marketMode" to engine.marketMode(),
            "stocksConfigured" to engine.equityKey.configured(),
            "demoStepMinutes" to scheduler?.demoStepMinutes,
            "marketTime" to engine.marketClock.now(),
            "tickSeconds" to app.strategyforge.engine.EngineScheduler.TICK_INTERVAL.seconds,
        )

    // ------------------------------------------------------------------ home, diagnostics, market data

    private fun home() {
        get("/v1/diagnostics") { _, _ -> diagnostics(engine.diagnostics.report()) }
        get("/v1/market-data/clock") { _, _ ->
            val mode = engine.marketMode()
            mapOf("mode" to if (mode == MarketMode.DEMO) "REPLAY" else "LIVE", "now" to engine.marketClock.now(), "synthetic" to (mode == MarketMode.DEMO))
        }
        get("/v1/market-data/fx/USD/CAD") { _, _ ->
            val fx = engine.market.latestFx("USD", "CAD") ?: engine.market.refreshFx("USD", "CAD")?.let { engine.market.latestFx("USD", "CAD") } ?: throw Problems.unavailable("fx-unavailable", "No USD/CAD rate is available yet")
            mapOf("base" to fx.base, "quote" to fx.quote, "rate" to fx.rate, "asOf" to fx.asOf)
        }
    }

    private fun diagnostics(d: DiagnosticsReport) =
        mapOf(
            "overall" to d.overall,
            "generatedAt" to d.generatedAt,
            "components" to d.components.map { mapOf("component" to it.component, "status" to it.status, "detail" to it.detail, "checkedAt" to it.checkedAt) },
        )

    // ------------------------------------------------------------------ portfolios and orders

    private fun portfolios() {
        get("/v1/portfolios") { r, _ -> engine.portfolios.list(r.query["includeArchived"] == "true").map { portfolio(it) } }
        post("/v1/portfolios", 201) { r, _ ->
            val b = obj(r)
            portfolio(engine.portfolios.create(PortfolioCreate(b.req("name"), b.dec("startingBalance"))))
        }
        get("/v1/portfolios/{id}") { _, g -> portfolio(engine.portfolios.get(uuid(g[0]))) }
        get("/v1/portfolios/{id}/summary") { _, g -> summary(engine.portfolios.summary(uuid(g[0]))) }
        post("/v1/portfolios/{id}/shorting") { r, g -> portfolio(engine.portfolios.setShorting(uuid(g[0]), obj(r).bool("enabled") ?: false)) }
        get("/v1/orders") { r, _ ->
            val statuses = r.query["status"]?.split(',')?.mapNotNull { s -> OrderStatus.entries.firstOrNull { it.name == s.trim() } }
            page(engine.orders.list(r.query["portfolioId"]?.let { uuid(it) }, statuses, limit(r)).map { order(it) })
        }
        post("/v1/orders", 201) { r, _ ->
            val b = obj(r)
            val req =
                OrderRequest(
                    uuid(b.req("portfolioId")),
                    b.req("symbol"),
                    enumOf<OrderSide>(b.req("side"), "side"),
                    enumOf<OrderType>(b.req("orderType"), "orderType"),
                    b.dec("quantity") ?: throw Problems.badRequest("missing-field", "quantity is required"),
                    b.dec("limitPrice"),
                    b.dec("stopPrice"),
                    b.str("timeInForce")?.let { enumOf<TimeInForce>(it, "timeInForce") } ?: TimeInForce.DAY,
                )
            val result = engine.orders.create(req, OrderSource.MANUAL)
            mapOf("order" to order(result.order), "risk" to mapOf("allowed" to result.risk.allowed, "reasons" to result.risk.reasons))
        }
        get("/v1/orders/{id}") { _, g ->
            val o = engine.orders.get(uuid(g[0]))
            mapOf("order" to order(o), "history" to engine.orders.statusHistory(o.id).map { mapOf("from" to it.from, "to" to it.to, "at" to it.at, "reason" to it.reason) })
        }
        post("/v1/orders/{id}/cancel") { r, g -> order(engine.orders.cancel(uuid(g[0]), obj(r).str("reason") ?: "Cancelled by owner")) }
    }

    private inline fun <reified E : Enum<E>> enumOf(
        v: String,
        field: String,
    ): E = enumValues<E>().firstOrNull { it.name == v.trim().uppercase() } ?: throw Problems.badRequest("invalid-$field", "Unsupported $field '$v'")

    private fun portfolio(p: Portfolio) =
        mapOf(
            "id" to p.id,
            "name" to p.name,
            "accountType" to p.accountType,
            "status" to p.status,
            "baseCurrency" to p.baseCurrency,
            "startingBalance" to p.startingBalance,
            "shortingEnabled" to p.shortingEnabled,
            "reconciliationStatus" to p.reconciliationStatus,
            "createdAt" to p.createdAt,
            "archivedAt" to p.archivedAt,
            "version" to p.version,
        )

    private fun position(v: PositionView) =
        mapOf(
            "instrumentId" to v.instrumentId,
            "symbol" to v.symbol,
            "assetClass" to v.assetClass,
            "side" to v.side,
            "quantity" to v.quantity,
            "costBasis" to v.costBasis,
            "averageCost" to v.averageCost,
            "marketPrice" to v.marketPrice,
            "marketValue" to v.marketValue,
            "unrealizedPnl" to v.unrealizedPnl,
            "priceTimestamp" to v.priceTimestamp,
            "priceStatus" to v.priceStatus,
            "manualReviewRequired" to v.manualReviewRequired,
        )

    private fun summary(s: PortfolioSummary) =
        mapOf(
            "portfolio" to portfolio(s.portfolio),
            "cash" to s.cash,
            "reservedCash" to s.reservedCash,
            "buyingPower" to s.buyingPower,
            "shortRequirement" to s.shortRequirement,
            "costBasis" to s.costBasis,
            "marketValue" to s.marketValue,
            "equity" to s.equity,
            "realizedPnl" to s.realizedPnl,
            "unrealizedPnl" to s.unrealizedPnl,
            "fees" to s.fees,
            "borrowFees" to s.borrowFees,
            "dividends" to s.dividends,
            "totalReturn" to s.totalReturn,
            "totalReturnPercent" to s.totalReturnPercent,
            "fullyPriced" to s.fullyPriced,
            "positions" to s.positions.map { position(it) },
            "asOf" to s.asOf,
        )

    private fun order(o: PaperOrder) =
        mapOf(
            "id" to o.id,
            "portfolioId" to o.portfolioId,
            "symbol" to o.symbol,
            "side" to o.side,
            "orderType" to o.orderType,
            "timeInForce" to o.timeInForce,
            "quantity" to o.quantity,
            "limitPrice" to o.limitPrice,
            "stopPrice" to o.stopPrice,
            "status" to o.status,
            "source" to o.source,
            "venue" to o.venue,
            "strategyId" to o.strategyId,
            "recommendationId" to o.recommendationId,
            "filledQuantity" to o.filledQuantity,
            "averageFillPrice" to o.averageFillPrice,
            "reservedAmount" to o.reservedAmount,
            "triggered" to o.triggered,
            "rejectionReason" to o.rejectionReason,
            "createdAt" to o.createdAt,
        )

    // ------------------------------------------------------------------ strategies, backtests, activation

    private fun strategies() {
        get("/v1/strategies") { r, _ -> engine.strategies.list(r.query["includeArchived"] == "true").map { strategy(it) } }
        post("/v1/strategies", 201) { r, _ ->
            val content = obj(r)["content"] ?: throw Problems.badRequest("missing-field", "content is required")
            val notes = obj(r).str("notes")
            val result = engine.strategies.import(content.toString().toByteArray(Charsets.UTF_8), "app-import.json", notes)
            strategyResult(result) + ("importNotes" to engine.strategies.importNotes(result.strategy.id)?.let { importNotes(it) })
        }
        get("/v1/strategies/{id}") { _, g ->
            val s = engine.strategies.get(uuid(g[0]))
            val v = s.currentVersionId?.let { engine.strategies.version(it) }
            val validation = v?.let { engine.strategies.latestValidation(it.id) }
            val explanation =
                v?.takeIf { it.validationStatus == "VALIDATED" }?.let {
                    runCatching {
                        app.strategyforge.engine.strategy.StrategyExplainer
                            .explain(engine.strategies.definition(it.id))
                    }.getOrNull()
                }
            mapOf(
                "strategy" to strategy(s),
                "currentVersion" to v?.let { version(it) },
                "validation" to validation?.let { validation(it) },
                "explanation" to explanation,
                "importNotes" to engine.strategies.importNotes(s.id)?.let { importNotes(it) },
                "plan" to
                    v?.takeIf { it.validationStatus != "VALIDATION_FAILED" }?.let {
                        runCatching { engine.strategies.definition(it.id) }.getOrNull()?.takeIf { d -> d.isPlan }?.let { d -> plan(d) }
                    },
                "activation" to engine.activations.active(s.id)?.let { activation(it) },
                "history" to engine.strategies.statusHistoryOf(s.id),
            )
        }
        post("/v1/strategies/{id}/validate") { _, g -> strategyResult(engine.strategies.revalidate(uuid(g[0]))) }
        post("/v1/strategies/{id}/archive") { _, g -> strategy(engine.strategies.archive(uuid(g[0]))) }
        post("/v1/strategies/{id}/plan-rules") { r, g ->
            val b = obj(r)
            strategyResult(
                engine.strategies.setPlanRules(
                    uuid(g[0]),
                    enumOf(b.req("conflictPolicy"), "conflictPolicy"),
                    enumOf(b.req("capitalPolicy"), "capitalPolicy"),
                    b.str("maximumOpenRiskPercent")?.let { v -> v.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it <= java.math.BigDecimal(100) } ?: throw Problems.badRequest("invalid-open-risk", "maximumOpenRiskPercent must be above 0 and at most 100") },
                ),
            )
        }
        get("/v1/backtests") { r, _ -> engine.backtests.list(r.query["strategyId"]?.let { uuid(it) }).map { backtest(it) } }
        post("/v1/backtests", 202) { r, _ ->
            val b = obj(r)
            backtest(
                engine.backtests.submit(
                    BacktestRequest(
                        uuid(b.req("strategyId")),
                        from = Instant.parse(b.req("from")),
                        to = Instant.parse(b.req("to")),
                        startingCapital = b.dec("startingCapital"),
                    ),
                ),
            )
        }
        get("/v1/autonomy/disclosure") { _, _ -> mapOf("version" to AutonomyDisclosure.VERSION, "text" to AutonomyDisclosure.TEXT) }
        post("/v1/strategies/{id}/activate", 201) { r, g ->
            val b = obj(r)
            val mode = b.str("mode")?.let { enumOf<ActivationMode>(it, "mode") } ?: ActivationMode.RECOMMENDATION
            // One strategy per asset class (D-035): replacing another asks what to do with its positions.
            val positions = b.str("positions")?.let { enumOf<app.strategyforge.engine.autonomy.PositionHandling>(it, "positions") }
            val result =
                engine.slots.activate(
                    uuid(g[0]),
                    ActivationRequest(mode, uuid(b.req("portfolioId")), b.dec("allocationPercent") ?: throw Problems.badRequest("missing-field", "allocationPercent is required"), b.bool("disclosureAccepted") ?: false, b.str("disclosureVersion")),
                    positions,
                )
            activation(result.slot.activation!!) +
                mapOf(
                    "replacedStrategyId" to result.replaced?.id,
                    "replacedStrategyName" to result.replaced?.name,
                    "positions" to result.positions,
                    "handedOver" to result.handedOver.map { holding(it) },
                    "closingOrders" to result.closingOrders,
                    "leftOpen" to result.leftOpen.map { holding(it) },
                )
        }
        post("/v1/strategies/{id}/deactivate") { r, g -> engine.strategyControl.deactivate(uuid(g[0]), obj(r).str("reason"))?.let { activation(it) } ?: mapOf("status" to "NOT_ACTIVE") }
        get("/v1/charts/candles") { r, _ ->
            val c =
                engine.charts.candles(
                    r.query["symbol"] ?: throw Problems.badRequest("missing-field", "symbol is required"),
                    r.query["timeframe"] ?: "1h",
                    r.query["bars"]?.toIntOrNull() ?: app.strategyforge.engine.reports.ChartService.DEFAULT_BARS,
                    r.query["portfolioId"]?.let { uuid(it) },
                    r.query["strategyId"]?.let { uuid(it) },
                )
            mapOf(
                "symbol" to c.symbol,
                "timeframe" to c.timeframe,
                "status" to c.status,
                "detail" to c.detail,
                "bars" to c.bars.map { b -> mapOf("t" to b.openTime, "o" to b.open, "h" to b.high, "l" to b.low, "c" to b.close, "v" to b.volume) },
                "trades" to c.trades.map { t -> mapOf("at" to t.at, "side" to t.side, "price" to t.price, "quantity" to t.quantity, "strategyId" to t.strategyId) },
            )
        }
        get("/v1/charts/equity/{id}") { r, g ->
            val c = engine.charts.equity(uuid(g[0]), r.query["range"] ?: "1M")
            mapOf(
                "portfolioId" to c.portfolioId,
                "range" to c.range,
                "points" to c.points.map { mapOf("at" to it.at, "value" to it.value) },
                "change" to c.change,
                "changePercent" to c.changePercent,
            )
        }
        get("/v1/scorecards") { r, _ -> engine.scorecards.all(r.query["assetClass"]).map { scorecard(it) } }
        get("/v1/strategies/{id}/scorecard") { _, g -> scorecard(engine.scorecards.scorecard(uuid(g[0]))) }
        post("/v1/scorecards/combine", 201) { r, _ ->
            val b = obj(r)
            val asset = b.req("assetClass")
            researchDetail(
                engine.research.startConversation(
                    app.strategyforge.engine.research.ConversationStart(
                        engine.scorecards.combineBrief(asset),
                        asset,
                        b.str("providerId")?.let { uuid(it) },
                        if (asset == "CRYPTO") "Better crypto strategy from my results" else "Better stock strategy from my results",
                    ),
                ),
            )
        }
        get("/v1/slots") { _, _ ->
            engine.slots.slots().map { s ->
                mapOf(
                    "assetClass" to s.assetClass,
                    "strategy" to s.strategy?.let { strategy(it) },
                    "activation" to s.activation?.let { activation(it) },
                    "holdings" to s.holdings.map { holding(it) },
                )
            }
        }
    }

    private fun scorecard(c: app.strategyforge.engine.reports.Scorecard) =
        mapOf(
            "strategy" to strategy(c.strategy),
            "live" to
                c.live.let { l ->
                    mapOf(
                        "closedTrades" to l.closedTrades,
                        "wins" to l.wins,
                        "losses" to l.losses,
                        "winRatePercent" to l.winRatePercent,
                        "realizedPnl" to l.realizedPnl,
                        "averagePnl" to l.averagePnl,
                        "bestTrade" to l.bestTrade,
                        "worstTrade" to l.worstTrade,
                        "maxDrawdown" to l.maxDrawdown,
                        "openPositions" to l.openPositions,
                        "activeDays" to l.activeDays,
                        "firstTradeAt" to l.firstTradeAt,
                        "lastTradeAt" to l.lastTradeAt,
                        "pnlSeries" to l.pnlSeries.map { mapOf("at" to it.at, "value" to it.value) },
                    )
                },
            "backtest" to
                c.backtest?.let { b ->
                    mapOf(
                        "backtestId" to b.backtestId,
                        "netReturnPercent" to b.netReturnPercent,
                        "maxDrawdownPercent" to b.maxDrawdownPercent,
                        "trades" to b.trades,
                        "winRatePercent" to b.winRatePercent,
                        "profitFactor" to b.profitFactor,
                        "completedAt" to b.completedAt,
                    )
                },
            "sampleWarning" to c.sampleWarning,
            "setups" to
                c.setups.map { s ->
                    mapOf(
                        "id" to s.id,
                        "name" to s.name,
                        "priority" to s.priority,
                        "liveClosedTrades" to s.liveClosedTrades,
                        "liveWinRatePercent" to s.liveWinRatePercent,
                        "liveRealizedPnl" to s.liveRealizedPnl,
                        "backtestTrades" to s.backtestTrades,
                        "backtestWinRatePercent" to s.backtestWinRatePercent,
                        "backtestNetPnl" to s.backtestNetPnl,
                        "backtestProfitFactor" to s.backtestProfitFactor,
                    )
                },
        )

    private fun holding(h: app.strategyforge.engine.autonomy.SlotHolding) = mapOf("portfolioId" to h.portfolioId, "symbol" to h.symbol, "side" to h.side, "quantity" to h.quantity)

    private fun strategy(s: StrategyView) =
        mapOf(
            "id" to s.id,
            "name" to s.name,
            "assetClass" to s.assetClass,
            "status" to s.status,
            "statusReason" to s.statusReason,
            "currentVersionId" to s.currentVersionId,
            "currentVersion" to s.currentVersion,
            "contentHash" to s.contentHash,
            "validationStatus" to s.validationStatus,
            "clonedFrom" to s.clonedFrom,
            "createdAt" to s.createdAt,
            "updatedAt" to s.updatedAt,
            "version" to s.version,
        )

    private fun version(v: StrategyVersionView) =
        mapOf(
            "id" to v.id,
            "strategyId" to v.strategyId,
            "versionNumber" to v.versionNumber,
            "content" to jackson(v.content),
            "contentHash" to v.contentHash,
            "source" to v.source,
            "sourceRef" to v.sourceRef,
            "validationStatus" to v.validationStatus,
            "createdAt" to v.createdAt,
        )

    private fun plan(d: app.strategyforge.engine.strategy.StrategyDefinition) =
        mapOf(
            "conflictPolicy" to d.plan.conflictPolicy,
            "capitalPolicy" to d.plan.capitalPolicy,
            "maximumOpenRiskPercent" to d.plan.maximumOpenRiskPercent,
            "longContext" to (d.plan.longWhen != null),
            "shortContext" to (d.plan.shortWhen != null),
            "setups" to
                d.setups.map { s ->
                    mapOf(
                        "id" to s.id,
                        "name" to s.name,
                        "description" to s.description,
                        "priority" to s.priority,
                        "direction" to s.def.direction,
                        "allocationPercent" to s.allocationPercent,
                        "maximumOpenPositions" to s.maximumOpenPositions,
                        "conditional" to (s.appliesWhen != null),
                    )
                },
        )

    private fun importNotes(n: app.strategyforge.engine.research.ImportNotes) = mapOf("readback" to n.readback, "furtherResearch" to n.furtherResearch, "stillMissing" to n.stillMissing, "other" to n.other)

    private fun validation(v: ValidationView) =
        mapOf(
            "id" to v.id,
            "status" to v.status,
            "issues" to v.issues.map { plain(it) },
            "unknownFields" to v.unknownFields,
            "validatedAt" to v.validatedAt,
        )

    private fun strategyResult(r: StrategyResult) = mapOf("strategy" to strategy(r.strategy), "version" to r.version?.let { version(it) }, "validation" to validation(r.validation), "explanation" to r.explanation)

    private fun backtest(b: BacktestView) =
        mapOf(
            "id" to b.id,
            "strategyId" to b.strategyId,
            "versionId" to b.versionId,
            "status" to b.status,
            "resultStatus" to b.resultStatus,
            "metrics" to jackson(b.metrics),
            "dataset" to jackson(b.dataset),
            "integrity" to jackson(b.integrity),
            "error" to b.error,
            "createdAt" to b.createdAt,
            "completedAt" to b.completedAt,
        )

    private fun activation(a: Activation) =
        mapOf(
            "id" to a.id,
            "strategyId" to a.strategyId,
            "portfolioId" to a.portfolioId,
            "mode" to a.mode,
            "allocationPercent" to a.allocationPercent,
            "status" to a.status,
            "fingerprint" to a.fingerprint,
            "disclosureVersion" to a.disclosureVersion,
            "createdAt" to a.createdAt,
            "endReason" to a.endReason,
        )

    // ------------------------------------------------------------------ recommendations

    private fun recommendations() {
        get("/v1/recommendations") { r, _ -> page(engine.recommendations.list(r.query["status"], r.query["strategyId"]?.let { uuid(it) }, limit(r)).map { rec(it) }) }
        get("/v1/recommendations/{id}") { _, g -> detail(engine.recommendations.detail(uuid(g[0]))) }
        post("/v1/recommendations/{id}/accept") { r, g ->
            val m = obj(r)["modification"] as? JsonObject
            val d = engine.recommendations.accept(uuid(g[0]), AcceptRequest(m?.let { Modification(it.dec("quantity"), it.dec("limitPrice")) }))
            mapOf("recommendation" to rec(d.recommendation), "orderId" to d.orderId, "orderStatus" to d.orderStatus, "detail" to d.detail)
        }
        post("/v1/recommendations/{id}/decline") { r, g -> rec(engine.recommendations.decline(uuid(g[0]), obj(r).str("reason"))) }
        post("/v1/recommendations/{id}/snooze") { r, g -> rec(engine.recommendations.snooze(uuid(g[0]), (obj(r).int("minutes") ?: 30).toLong())) }
        post("/v1/recommendations/{id}/pause-strategy") { _, g -> rec(engine.recommendations.pauseStrategy(uuid(g[0])) { s, reason -> engine.strategyControl.deactivate(s, reason) }) }
        get("/v1/signals") { r, _ -> engine.signals.list(uuid(r.query["strategyId"] ?: throw Problems.badRequest("missing-field", "strategyId is required")), limit(r)) }
    }

    private fun rec(r: Recommendation) =
        mapOf(
            "id" to r.id,
            "signalId" to r.signalId,
            "strategyId" to r.strategyId,
            "strategyName" to r.strategyName,
            "portfolioId" to r.portfolioId,
            "instrumentId" to r.instrumentId,
            "symbol" to r.symbol,
            "status" to r.status,
            "statusReason" to r.statusReason,
            "side" to r.side,
            "quantity" to r.quantity,
            "orderType" to r.orderType,
            "limitPrice" to r.limitPrice,
            "referencePrice" to r.referencePrice,
            "maxDeviationPercent" to r.maxDeviationPercent,
            "expiresAt" to r.expiresAt,
            "snoozedUntil" to r.snoozedUntil,
            "riskEvaluationId" to r.riskEvaluationId,
            "orderId" to r.orderId,
            "rationale" to r.rationale,
            "triggeredRules" to r.triggeredRules,
            "versionHash" to r.versionHash,
            "marketSnapshotId" to r.marketSnapshotId,
            "createdAt" to r.createdAt,
            "decidedAt" to r.decidedAt,
        )

    private fun detail(d: RecommendationDetail) =
        mapOf(
            "recommendation" to rec(d.recommendation),
            // The app shows Accept only with a token. On the phone the engine checks status and
            // expiry itself (D-029), so the "token" is simply the pending recommendation's id.
            "actionToken" to d.recommendation.takeIf { it.status.name == "PENDING" }?.let { mapOf("token" to it.id, "expiresAt" to it.expiresAt) },
            "decisions" to d.decisions.map { m -> m.mapValues { (_, v) -> if (v is JsonNode) jackson(v) else v } },
            "disclaimer" to d.disclaimer,
        )

    // ------------------------------------------------------------------ inbox

    private fun inbox() {
        get("/v1/notifications") { r, _ -> page(engine.notifications.list(limit(r), r.query["unreadOnly"] == "true").map { notification(it) }) }
        get("/v1/notifications/unread-count") { _, _ -> mapOf("unread" to engine.notifications.unreadCount()) }
        get("/v1/notifications/{id}") { _, g -> notification(engine.notifications.get(uuid(g[0]))) }
        post("/v1/notifications/{id}/read") { _, g -> notification(engine.notifications.markRead(uuid(g[0]))) }
        post("/v1/notifications/read-all") { _, _ -> mapOf("updated" to engine.notifications.markAllRead()) }
    }

    private fun notification(n: NotificationView) =
        mapOf(
            "id" to n.id,
            "category" to n.category,
            "severity" to n.severity,
            "critical" to n.critical,
            "title" to n.title,
            "body" to n.body,
            "redactedTitle" to n.redactedTitle,
            "redactedBody" to n.redactedBody,
            "entityType" to n.entityType,
            "entityId" to n.entityId,
            "deepLink" to n.deepLink,
            // Kept for the Version 1 model: local notifications replace push (D-027).
            "pushStatus" to if (n.shown) "SHOWN" else "PENDING",
            "createdAt" to n.createdAt,
            "readAt" to n.readAt,
        )

    // ------------------------------------------------------------------ emergency controls (never cached by the app)

    private fun emergency() {
        get("/v1/emergency") { _, _ -> emergencyState(engine.emergency.state()) }
        post("/v1/emergency/pause-all") { r, _ -> emergencyResult(engine.emergency.pauseAll(toggle(r))) }
        post("/v1/emergency/prevent-new-positions") { r, _ -> emergencyResult(engine.emergency.preventNewPositions(toggle(r))) }
        post("/v1/emergency/cancel-pending-orders") { r, _ -> emergencyResult(engine.emergency.cancelPending(obj(r).str("reason"))) }
        post("/v1/emergency/disable-autonomous") { r, _ -> emergencyResult(engine.emergency.disableAutonomous(obj(r).str("reason"))) }
        post("/v1/emergency/close-all-simulated-positions") { r, _ -> emergencyResult(engine.emergency.closeAll(CloseAllRequest(obj(r).str("confirmation"), obj(r).str("portfolioId")?.let { uuid(it) }))) }
    }

    private fun toggle(r: LocalRequest) = ToggleRequest(obj(r).bool("enabled") ?: throw Problems.badRequest("missing-field", "enabled is required"), obj(r).str("reason"))

    private fun emergencyState(s: EmergencyState) = mapOf("pauseAll" to s.pauseAll, "preventNewPositions" to s.preventNewPositions, "reason" to s.reason, "updatedAt" to s.updatedAt, "version" to s.version)

    private fun emergencyResult(r: EmergencyResult) = mapOf("action" to r.action, "state" to emergencyState(r.state), "affected" to r.affected, "detail" to r.detail)

    // ------------------------------------------------------------------ settings and providers

    private fun settingsAndProviders() {
        get("/v1/settings") { _, _ -> settings(engine.settings.get()) }
        put("/v1/settings") { r, _ ->
            val b = obj(r)
            val cur = engine.settings.get()
            val next =
                cur.copy(
                    timezone = b.str("timezone") ?: cur.timezone,
                    displayCurrency = b.str("displayCurrency") ?: cur.displayCurrency,
                    showCadEquivalent = b.bool("showCadEquivalent") ?: cur.showCadEquivalent,
                    theme = b.str("theme") ?: cur.theme,
                    notifications =
                        b["notifications"]?.let {
                            EngineJson.decodeFromJsonElement(
                                app.strategyforge.engine.settings.NotificationPreferences
                                    .serializer(),
                                it,
                            )
                        } ?: cur.notifications,
                    privacy =
                        b["privacy"]?.let {
                            EngineJson.decodeFromJsonElement(
                                app.strategyforge.engine.settings.PrivacyPreferences
                                    .serializer(),
                                it,
                            )
                        } ?: cur.privacy,
                    portfolioDefaults = b["portfolioDefaults"]?.let { EngineJson.decodeFromJsonElement(PortfolioDefaults.serializer(), it) } ?: cur.portfolioDefaults,
                )
            settings(engine.settings.update(next, version(r) ?: cur.version))
        }
        get("/v1/providers") { _, _ -> marketSources() + engine.aiProviders.list(includeInactive = true).map { provider(it) } }
        post("/v1/providers", 201) { r, _ ->
            val b = obj(r)
            val type = enumOf<AiProviderType>(b.req("providerType"), "providerType")
            val settings = (b["settings"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() } ?: type.presets
            provider(engine.aiProviders.create(type, b.str("displayName") ?: type.label, settings, b.str("credential")))
        }
        get("/v1/providers/types") { _, _ ->
            AiProviderType.entries.map { t ->
                mapOf(
                    "providerType" to t.name,
                    "label" to t.label,
                    "presets" to t.presets,
                    "keyUrl" to t.keyUrl,
                    "settings" to t.settings.map { (k, v) -> mapOf("name" to k, "type" to v.type.name, "required" to v.required) },
                )
            }
        }
        put("/v1/providers/{id}") { r, g ->
            val b = obj(r)
            val cur = engine.aiProviders.get(uuid(g[0]))
            val settings = (b["settings"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() } ?: cur.settings
            provider(engine.aiProviders.update(cur.id, b.str("displayName") ?: cur.displayName, settings))
        }
        post("/v1/providers/{id}/activate") { r, g -> provider(engine.aiProviders.setActive(uuid(g[0]), obj(r).bool("active") ?: true)) }
        put("/v1/providers/{id}/credential") { r, g -> provider(engine.aiProviders.setKey(uuid(g[0]), obj(r).str("credential"))) }
        post("/v1/providers/{id}/test") { _, g ->
            if (g[0] == COINBASE_ID) {
                val t = engine.sources.forClass(app.strategyforge.engine.market.AssetClass.CRYPTO)?.diagnose(engine.wall.instant())
                mapOf("id" to COINBASE_ID, "kind" to "MARKET_DATA", "providerType" to "COINBASE", "displayName" to "Coinbase (crypto, real-time)", "active" to (engine.marketMode() == MarketMode.LIVE), "lastTestStatus" to t?.status, "lastTestDetail" to t?.detail)
            } else {
                provider(engine.aiProviders.test(uuid(g[0])))
            }
        }
    }

    private fun marketSources(): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "id" to COINBASE_ID,
                "kind" to "MARKET_DATA",
                "providerType" to "COINBASE",
                "displayName" to "Coinbase (crypto, real-time, no key)",
                "active" to (engine.marketMode() == MarketMode.LIVE),
                "lastTestStatus" to null,
                "lastTestDetail" to if (engine.marketMode() == MarketMode.LIVE) "Live crypto data" else "Demo mode uses replay data",
            ),
        )

    private fun settings(s: OwnerSettings) =
        mapOf(
            "timezone" to s.timezone,
            "displayCurrency" to s.displayCurrency,
            "showCadEquivalent" to s.showCadEquivalent,
            "theme" to s.theme,
            "notifications" to
                EngineJson.encodeToJsonElement(
                    app.strategyforge.engine.settings.NotificationPreferences
                        .serializer(),
                    s.notifications,
                ),
            "privacy" to
                EngineJson.encodeToJsonElement(
                    app.strategyforge.engine.settings.PrivacyPreferences
                        .serializer(),
                    s.privacy,
                ),
            "portfolioDefaults" to EngineJson.encodeToJsonElement(PortfolioDefaults.serializer(), s.portfolioDefaults),
            "updatedAt" to s.updatedAt,
            "version" to s.version,
        )

    private fun provider(p: AiProviderView) =
        mapOf(
            "id" to p.id,
            "kind" to "AI",
            "providerType" to p.providerType,
            "displayName" to p.displayName,
            "settings" to p.settings,
            "credentialConfigured" to p.keyConfigured,
            "credentialFingerprint" to p.keyFingerprint,
            "active" to p.active,
            "lastTestStatus" to p.lastTestStatus,
            "lastTestDetail" to p.lastTestDetail,
        )

    // ------------------------------------------------------------------ AI research and budget

    private fun research() {
        get("/v1/research") { _, _ -> engine.research.list().map { session(it) } }
        post("/v1/research", 201) { r, _ ->
            val b = obj(r)
            session(
                engine.research.create(
                    ResearchCreate(
                        b.req("title"),
                        uuid(b.req("providerId")),
                        b.req("assetClass"),
                        (b["universe"] ?: throw Problems.badRequest("missing-field", "universe is required")).jsonArray.map { it.jsonPrimitive.content },
                        b.req("horizon"),
                        b.req("timeframe"),
                        b.req("approach"),
                        b.req("prompt"),
                        b.bool("retrieval") ?: false,
                        b.int("maxRequests") ?: 3,
                        b.dec("maxCostUsd") ?: BigDecimal("2"),
                    ),
                ),
            )
        }
        get("/v1/research/budget") { _, _ -> budget(engine.aiBudget.get()) }
        put("/v1/research/budget") { r, _ ->
            val b = obj(r)
            val cur = engine.aiBudget.get()
            budget(
                engine.aiBudget.update(
                    AiBudgetUpdate(b.dec("monthlyCostLimitUsd") ?: cur.monthlyCostLimitUsd, b.int("dailyRequestLimit") ?: cur.dailyRequestLimit, b.int("maxOutputTokens") ?: cur.maxOutputTokens, b.int("maxInputChars") ?: cur.maxInputChars),
                    version(r) ?: cur.version,
                ),
            )
        }
        // Research conversations (D-034): a first message, then replies.
        post("/v1/research/conversations", 201) { r, _ ->
            val b = obj(r)
            researchDetail(
                engine.research.startConversation(
                    app.strategyforge.engine.research
                        .ConversationStart(b.req("message"), b.str("assetClass") ?: "CRYPTO", b.str("providerId")?.let { uuid(it) }, b.str("title")),
                ),
            )
        }
        post("/v1/research/imports", 201) { r, _ ->
            val b = obj(r)
            researchDetail(
                engine.research.importResearch(
                    app.strategyforge.engine.research
                        .ResearchImport(b.req("text"), b.str("assetClass") ?: "CRYPTO", b.str("providerId")?.let { uuid(it) }, b.str("title")),
                ),
            )
        }
        get("/v1/research/authoring-prompt") { r, _ -> mapOf("prompt" to engine.research.authoringPrompt(r.query["assetClass"] ?: "CRYPTO")) }
        get("/v1/research/limits") { _, _ -> mapOf("maxMessageChars" to engine.research.messageLimit()) }
        post("/v1/research/{id}/messages", 202) { r, g -> researchDetail(engine.research.message(uuid(g[0]), obj(r).req("message"))) }
        get("/v1/research/{id}") { _, g -> researchDetail(engine.research.detail(uuid(g[0]))) }
        post("/v1/research/{id}/run", 202) { _, g -> researchDetail(engine.research.run(uuid(g[0]))) }
        post("/v1/research/{id}/review") { r, g -> researchDetail(engine.research.review(uuid(g[0]), ReviewRequest(obj(r).req("decision"), obj(r).str("note")))) }
        post("/v1/research/{id}/compile", 202) { _, g -> researchDetail(engine.research.compile(uuid(g[0]))) }
        post("/v1/research/{id}/edits", 201) { r, g ->
            researchDetail(
                engine.research.edit(
                    uuid(g[0]),
                    app.strategyforge.engine.research
                        .EditRequest(obj(r).req("content"), obj(r).str("note")),
                ),
            )
        }
    }

    private fun session(s: ResearchSession) =
        mapOf(
            "id" to s.id,
            "title" to s.title,
            "providerId" to s.providerId,
            "providerType" to s.providerType,
            "model" to s.model,
            "assetClass" to s.assetClass,
            "universe" to s.universe,
            "horizon" to s.horizon,
            "timeframe" to s.timeframe,
            "approach" to s.approach,
            "prompt" to s.prompt,
            "retrieval" to s.retrieval,
            "status" to s.status,
            "reviewStatus" to s.reviewStatus,
            "reviewNote" to s.reviewNote,
            "requestsUsed" to s.requestsUsed,
            "maxRequests" to s.maxRequests,
            "costUsd" to s.costUsd,
            "maxCostUsd" to s.maxCostUsd,
            "createdAt" to s.createdAt,
            "updatedAt" to s.updatedAt,
            // Conversations have no fixed timeframe; the AI proposes it (D-034).
            "conversation" to s.timeframe.isBlank(),
            "imported" to (s.importChunk != null),
        )

    private fun researchDetail(d: ResearchDetail) =
        mapOf(
            "session" to session(d.session),
            "runs" to
                d.runs.map { r ->
                    mapOf(
                        "id" to r.id,
                        "purpose" to r.purpose,
                        "providerType" to r.providerType,
                        "model" to r.model,
                        "promptVersion" to r.promptVersion,
                        "status" to r.status,
                        "label" to r.label,
                        "failureCode" to r.failureCode,
                        "failureDetail" to r.failureDetail,
                        "responseText" to r.responseText,
                        "inputTokens" to r.inputTokens,
                        "outputTokens" to r.outputTokens,
                        "estimatedCostUsd" to r.estimatedCostUsd,
                        "reservedCostUsd" to r.reservedCostUsd,
                        "sources" to r.sources.map { s -> mapOf("url" to s.url, "title" to s.title, "citedText" to s.citedText) },
                        "startedAt" to r.startedAt,
                        "completedAt" to r.completedAt,
                        "ownerMessage" to r.ownerMessage,
                    )
                },
            "edits" to d.edits.map { e -> mapOf("id" to e.id, "baseRunId" to e.baseRunId, "content" to e.content, "note" to e.note, "createdAt" to e.createdAt) },
            "compilations" to d.compilations.map { c -> mapOf("id" to c.id, "status" to c.status, "strategyId" to c.strategyId, "versionId" to c.versionId, "contentHash" to c.contentHash, "issues" to jackson(c.issues), "createdAt" to c.createdAt) },
            "disclaimer" to d.disclaimer,
        )

    private fun budget(b: AiBudget) =
        mapOf(
            "monthlyCostLimitUsd" to b.monthlyCostLimitUsd,
            "dailyRequestLimit" to b.dailyRequestLimit,
            "maxOutputTokens" to b.maxOutputTokens,
            "maxInputChars" to b.maxInputChars,
            "monthCostUsd" to b.monthCostUsd,
            "todayRequests" to b.todayRequests,
            "updatedAt" to b.updatedAt,
            "version" to b.version,
        )

    // ------------------------------------------------------------------ reports and exports

    private fun reportsAndExports() {
        get("/v1/reports/portfolios/{id}") { _, g ->
            val r = engine.reports.portfolio(uuid(g[0]))
            mapOf(
                "summary" to summary(r.summary),
                "equity" to r.equity.map { mapOf("at" to it.at, "value" to it.value) },
                "drawdown" to r.drawdown.map { mapOf("at" to it.at, "value" to it.value) },
                "maxDrawdownPercent" to r.maxDrawdownPercent,
                "periodReturnPercent" to r.periodReturnPercent,
                "costs" to mapOf("commissions" to r.costs.commissions, "spread" to r.costs.spread, "slippage" to r.costs.slippage, "borrow" to r.costs.borrow, "dividends" to r.costs.dividends, "total" to r.costs.total),
                "byAsset" to r.byAsset.map { attribution(it) },
                "byStrategy" to r.byStrategy.map { attribution(it) },
                "benchmarks" to r.benchmarks.map { mapOf("symbol" to it.symbol, "startClose" to it.startClose, "endClose" to it.endClose, "returnPercent" to it.returnPercent, "note" to it.note) },
                "disclaimer" to r.disclaimer,
            )
        }
        get("/v1/reports/portfolios/{id}/outcomes") { _, g ->
            val o = engine.reports.outcomes(uuid(g[0]))
            mapOf("portfolioId" to o.portfolioId, "bySource" to o.bySource.map { outcome(it) }, "byRecommendationDecision" to o.byRecommendationDecision.map { outcome(it) }, "disclaimer" to o.disclaimer)
        }
        get("/v1/exports/{id}") { r, g ->
            val doc = engine.exports.export(g[0], r.query["portfolioId"]?.let { uuid(it) })
            val format = r.query["format"] ?: "json"
            val (bytes, type) =
                when (format) {
                    "csv" -> ExportService.csv(doc).toByteArray(Charsets.UTF_8) to "text/csv; charset=utf-8"
                    "json" ->
                        mapOf(
                            "dataset" to doc.dataset,
                            "schemaVersion" to doc.schemaVersion,
                            "generatedAt" to doc.generatedAt,
                            "portfolioId" to doc.portfolioId,
                            "columns" to doc.columns,
                            "rows" to doc.rows,
                            "totals" to doc.totals,
                            "reconciliation" to doc.reconciliation.map { c -> mapOf("name" to c.name, "expected" to c.expected, "actual" to c.actual, "ok" to c.ok) },
                            "disclaimer" to doc.disclaimer,
                        ).toJsonElement().toString().toByteArray(Charsets.UTF_8) to "application/json"
                    else -> throw Problems.badRequest("invalid-format", "format must be csv or json")
                }
            engine.exports.recordExport(doc, format, bytes)
            LocalResponse(
                200,
                bytes,
                type,
                mapOf(
                    "Content-Disposition" to "attachment; filename=\"strategyforge-${doc.dataset}-v${doc.schemaVersion}.$format\"",
                    "X-StrategyForge-Schema-Version" to doc.schemaVersion.toString(),
                    "X-StrategyForge-Reconciled" to doc.reconciled.toString(),
                ),
            )
        }
    }

    private fun backups() {
        get("/v1/backups") { _, _ -> mapOf("items" to engine.backups.list().map { backupFile(it) }) }
        post("/v1/backups", 201) { _, _ ->
            val b = engine.backups.create()
            mapOf("file" to backupFile(b.file), "sha256" to b.sha256, "schemaVersion" to b.schemaVersion, "tables" to b.tables, "rows" to b.rows)
        }
        post("/v1/backups/{id}/verify") { _, g ->
            val v = engine.backups.verify(g[0])
            mapOf("name" to v.name, "valid" to v.valid, "schemaVersion" to v.schemaVersion, "createdAt" to v.createdAt, "tables" to v.tables, "rows" to v.rows, "error" to v.error)
        }
        post("/v1/backups/{id}/restore") { _, g ->
            val r = engine.backups.restore(g[0])
            mapOf("restoredFrom" to r.restoredFrom, "safetyBackup" to r.safetyBackup, "tables" to r.tables, "rows" to r.rows, "restartRequired" to true)
        }
    }

    private fun backupFile(f: app.strategyforge.engine.operations.BackupFileView) = mapOf("name" to f.name, "sizeBytes" to f.sizeBytes, "modifiedAt" to f.modifiedAt)

    private fun attribution(a: Attribution) = mapOf("key" to a.key, "label" to a.label, "realizedPnl" to a.realizedPnl, "unrealizedPnl" to a.unrealizedPnl, "trades" to a.trades)

    private fun outcome(o: OutcomeGroup) = mapOf("group" to o.group, "count" to o.count, "realizedPnl" to o.realizedPnl, "averagePnl" to o.averagePnl, "note" to o.note)

    companion object {
        /** The built-in keyless crypto source shown alongside AI providers. */
        const val COINBASE_ID = "00000000-0000-0000-0000-00000000c0b1"

        /** Strategy status names the app shows; kept here so a rename is caught at compile time. */
        val ACTIVE_STATUSES = setOf(StrategyStatus.ACTIVE_RECOMMENDATION.name, StrategyStatus.ACTIVE_AUTONOMOUS.name)
    }
}
