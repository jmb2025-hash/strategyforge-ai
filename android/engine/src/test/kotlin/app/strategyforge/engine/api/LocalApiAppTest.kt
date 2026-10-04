package app.strategyforge.engine.api

import app.strategyforge.android.core.api.ApiClient
import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.api.TokenStore
import app.strategyforge.android.core.cache.CachedResource
import app.strategyforge.android.core.cache.MemoryCacheStore
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.OrderDraft
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.state.BudgetForm
import app.strategyforge.android.core.state.ExportDataset
import app.strategyforge.android.core.state.ExportFormat
import app.strategyforge.android.core.state.ExportRequest
import app.strategyforge.engine.Engine
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.research.Fixtures
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.TestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.sql.DriverManager

/**
 * The app's own data layer (core Repository, ApiClient, cache and models) running against the
 * on-device engine through [LocalApiInterceptor], exactly as on the phone (D-031). Every response
 * must decode into the app's models; a shape mismatch fails here instead of on the device.
 */
class LocalApiAppTest {
    @TempDir lateinit var backups: File

    private val host = EngineHost("sf-engine-test")
    private lateinit var engine: Engine
    private lateinit var repo: Repository
    private lateinit var client: ApiClient

    private val ai = MockWebServer().also { it.start() }

    private fun start(
        wall: java.time.Clock = java.time.Clock.systemUTC(),
        tsx: app.strategyforge.engine.tsx.TsxHistoryProvider? = null,
    ) {
        engine =
            host.call {
                Engine(
                    JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
                    wall,
                    fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
                    allowLocalProviderHttp = true,
                    backupDir = { backups },
                    tsxProvider = { tsx },
                ).also { it.tsx.pauseMs = 0 }
            }
        val api = host.call { LocalApi(engine) }
        val http = OkHttpClient.Builder().addInterceptor(LocalApiInterceptor(host, api::handle)).build()
        val tokens =
            object : TokenStore {
                override fun token(): String? = null

                override fun save(token: String?) {}
            }
        client = ApiClient({ LocalApiInterceptor.BASE_URL }, http, tokens, io = Dispatchers.Unconfined, retryDelaysMs = emptyList())
        repo = Repository(client, CachedResource(MemoryCacheStore()), tokens)
    }

    @AfterEach
    fun stop() {
        host.close()
        ai.shutdown()
    }

    private fun <T> Flow<Resource<T>>.value(): T =
        runBlocking {
            when (val last = toList().last()) {
                is Resource.Data -> {
                    assertThat(last.error).`as`("refresh error").isNull()
                    last.value
                }
                is Resource.Failure -> throw last.error
                Resource.Loading -> error("still loading")
            }
        }

    private fun advance(minutes: Long) = host.call { engine.replay.advance(minutes, 1) }

    @Test
    fun `the app's screens work end to end against the on-device engine`() {
        start()
        runBlocking {
            assertThat(repo.bootstrapStatus().bootstrapped).isTrue()

            // Portfolio and a manual order that fills on the next replay step.
            val p = repo.createPortfolio("Main", "100000")
            assertThat(BigDecimal(p.startingBalance)).isEqualByComparingTo("100000")
            val order = repo.placeOrder(OrderDraft(p.id, "BTC-USD", "BUY", "MARKET", "0.01", timeInForce = "GTC"))
            assertThat(order.status).isIn("PENDING", "ACCEPTED", "FILLED")
            advance(2)
            val orders = repo.orders(p.id).value()
            assertThat(orders.items.single().status).isEqualTo("FILLED")

            val home = repo.dashboard().value()
            assertThat(
                home.primary!!
                    .positions
                    .single()
                    .symbol,
            ).isEqualTo("BTC-USD")
            assertThat(home.emergency.pauseAll).isFalse()
            assertThat(home.clock!!.synthetic).isTrue()
            assertThat(home.diagnostics.components).isNotEmpty()

            // Strategy import, backtest and Recommendation Mode through the app's calls.
            val content = JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong("App Rec", "1m", symbol = "SOL-USD", quantity = "2"))
            val imported = repo.importStrategy(content)
            assertThat(imported.strategy.status).isEqualTo("VALIDATED")
            val sid = imported.strategy.id
            val bt = repo.runBacktest(sid, "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z", "100000")
            assertThat(bt.status).isEqualTo("COMPLETED")
            assertThat(repo.backtests(sid)).hasSize(1)
            val detail = repo.strategy(sid).value()
            assertThat(detail.strategy.status).isEqualTo("PAPER_ELIGIBLE")
            assertThat(detail.currentVersion!!.contentHash).isNotBlank()

            // Autonomous Mode needs a recent device unlock; the app sees the usual "confirm it's you".
            val denied = assertThrows<ApiError.Http> { runBlocking { repo.activate(sid, p.id, "50", true, repo.disclosure().version) } }
            assertThat(denied.recentAuthRequired).isTrue()

            val activation = repo.activate(sid, p.id, "50", false, null)
            assertThat(activation.mode).isEqualTo("RECOMMENDATION")
            advance(1)
            val pending = repo.recommendations("PENDING").value().items
            assertThat(pending).isNotEmpty()
            val recDetail = repo.recommendation(pending.first().id)
            val token = recDetail.actionToken!!.token
            val decision = repo.accept(pending.first().id, token, null, null, "idem-1")
            assertThat(decision.recommendation.status).isEqualTo("ACCEPTED")
            assertThat(decision.orderId).isNotNull()
            repo.deactivate(sid)

            // Reports, outcomes and exports.
            val report = repo.report(p.id).value()
            assertThat(report.report.summary.portfolio.id).isEqualTo(p.id)
            assertThat(report.report.disclaimer).isNotBlank()
            val csv = repo.export(ExportRequest.of(ExportDataset.LEDGER, ExportFormat.CSV, p.id).getOrThrow())
            assertThat(csv.fileName).isEqualTo("strategyforge-ledger-v1.csv")
            assertThat(String(csv.bytes)).contains("BTC-USD")

            // Inbox and emergency controls.
            repo.notifications().value()
            repo.markAllRead()
            assertThat(repo.pauseAll(true).state.pauseAll).isTrue()
            val release = assertThrows<ApiError.Http> { runBlocking { repo.pauseAll(false) } }
            assertThat(release.recentAuthRequired).`as`("releasing a pause needs a recent unlock").isTrue()
            host.call { engine.auth.confirmed() }
            assertThat(repo.pauseAll(false).state.pauseAll).isFalse()
            assertThat(repo.emergency().pauseAll).isFalse()

            // Settings with optimistic concurrency.
            val settings = repo.settings().value()
            val saved = repo.updateSettings(settings, "America/Toronto", "CAD", true, "DARK")
            assertThat(saved.timezone).isEqualTo("America/Toronto")
            assertThat(saved.version).isGreaterThan(settings.version)
            val stale = assertThrows<ApiError.Http> { runBlocking { repo.updateSettings(settings, "UTC", "USD", false, "SYSTEM") } }
            assertThat(stale.status).isIn(409, 412)

            // Providers (built-in Coinbase source), research list and AI budget.
            assertThat(repo.providers().value().map { it.providerType }).contains("COINBASE")
            assertThat(repo.research().value()).isEmpty()
            val budget = repo.aiBudget()
            val lowered = repo.updateAiBudget(budget, BudgetForm(BigDecimal("1.00"), budget.dailyRequestLimit, budget.maxOutputTokens, budget.maxInputChars))
            assertThat(BigDecimal(lowered.monthlyCostLimitUsd)).isEqualByComparingTo("1.00")

            // FX is optional in demo mode; the call must not throw.
            repo.usdCad()
        }
    }

    @Test
    fun `backups are created, verified and restored only after a device unlock`() {
        start()
        runBlocking {
            val p = repo.createPortfolio("Before backup", "5000")
            val backup = repo.createBackup()
            assertThat(backup.rows).isGreaterThan(0)
            assertThat(repo.backups().map { it.name }).contains(backup.file.name)
            assertThat(repo.verifyBackup(backup.file.name).valid).isTrue()

            repo.createPortfolio("After backup", "7000")
            val denied = assertThrows<ApiError.Http> { runBlocking { client.post("/v1/backups/${backup.file.name}/restore") } }
            assertThat(denied.recentAuthRequired).isTrue()

            host.call { engine.auth.confirmed() }
            val restored = repo.restoreBackup(backup.file.name)
            assertThat(restored.safetyBackup).isNotEqualTo(backup.file.name)
            repo.clearCache()
            assertThat(repo.portfolios().value().map { it.name }).containsExactly("Before backup")
            assertThat(
                repo
                    .portfolios()
                    .value()
                    .single()
                    .id,
            ).isEqualTo(p.id)
            assertThat(host.call { engine.audit.verifyChain() }).`as`("audit chain intact after restore").isNull()

            // A damaged file is reported as invalid, never restored.
            val damaged = File(backups, backup.file.name)
            damaged.writeBytes(damaged.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
            assertThat(repo.verifyBackup(backup.file.name).valid).isFalse()
        }
    }

    @Test
    fun `AI research runs, is reviewed and compiles into a strategy through the app's calls`() {
        start()
        runBlocking {
            val created =
                client.post(
                    "/v1/providers",
                    buildJsonObject {
                        put("providerType", "ANTHROPIC")
                        put("displayName", "Claude")
                        put("credential", "sk-test-DO-NOT-LEAK-0000000000")
                        put(
                            "settings",
                            buildJsonObject {
                                put("model", "claude-opus-5")
                                put("baseUrl", ai.url("/").toString().trimEnd('/'))
                                put("timeoutSeconds", "10")
                                put("maxOutputTokens", "4000")
                                put("inputPricePerMillionTokensUsd", "5")
                                put("outputPricePerMillionTokensUsd", "25")
                                put("webSearchEnabled", "true")
                                put("webSearchPricePerThousandUsd", "10")
                                put("maxSearchesPerRequest", "3")
                            },
                        )
                    },
                )
            assertThat(created.body.toString()).doesNotContain("DO-NOT-LEAK").contains("\"credentialConfigured\":true")
            val providerId =
                created.body.jsonObject["id"]!!
                    .jsonPrimitive.content
            assertThat(repo.providers().value().map { it.providerType }).contains("ANTHROPIC", "COINBASE")

            val session =
                repo.createResearch(
                    buildJsonObject {
                        put("title", "BTC momentum")
                        put("providerId", providerId)
                        put("assetClass", "CRYPTO")
                        put("universe", JsonArray(listOf(JsonPrimitive("BTC-USD"))))
                        put("horizon", "weeks")
                        put("timeframe", "1h")
                        put("approach", "trend following")
                        put("prompt", "Research a simple hourly momentum rule for BTC.")
                        put("retrieval", true)
                        put("maxRequests", 4)
                        put("maxCostUsd", "3")
                    },
                )
            ai.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.anthropicWithCitations))
            val ran = repo.runResearch(session.id)
            assertThat(ran.runs.single().status).isEqualTo("SUCCEEDED")
            assertThat(
                ran.runs
                    .single()
                    .sources
                    .map { it.url },
            ).containsExactly("https://example.org/momentum-study")
            assertThat(repo.reviewResearch(session.id, true, "checked").session.reviewStatus).isEqualTo("REVIEWED")

            val strategy = JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong("AI Momentum", "1h"))
            ai.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.anthropicText(strategy, "end_turn")))
            val compiled = repo.compileResearch(session.id)
            val c = compiled.compilations.single()
            assertThat(c.status).isEqualTo("COMPILED")
            assertThat(
                repo
                    .strategy(c.strategyId!!)
                    .value()
                    .strategy.name,
            ).isEqualTo("AI Momentum")
            assertThat(
                repo
                    .research()
                    .value()
                    .single()
                    .costUsd,
            ).isNotEqualTo("0")
        }
    }

    @Test
    fun `device unlock, runtime mode and the provider catalog are served for the phone screens`() {
        start()
        runBlocking {
            val p = repo.createPortfolio("Auto", "100000")
            val content = JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong("App Auto", "1m", symbol = "ETH-USD", quantity = "0.1"))
            val sid = repo.importStrategy(content).strategy.id
            repo.runBacktest(sid, "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z", "100000")
            val version = repo.disclosure().version
            assertThrows<ApiError.Http> { runBlocking { repo.activate(sid, p.id, "50", true, version) } }
            // What the app does after the device-lock prompt succeeds.
            repo.reauthenticate("", null)
            assertThat(repo.activate(sid, p.id, "50", true, version).mode).isEqualTo("AUTONOMOUS")

            assertThat(repo.runtime().marketMode).isEqualTo("DEMO")
            assertThat(repo.setMarketMode("LIVE").marketMode).isEqualTo("LIVE")
            assertThat(host.call { engine.marketMode().name }).isEqualTo("LIVE")
            val noScheduler = assertThrows<ApiError.Http> { runBlocking { repo.setDemoSpeed(5) } }
            assertThat(noScheduler.status).isEqualTo(503)

            // Stock data key (Twelve Data): behind the device lock, never echoed back.
            assertThat(repo.stockData().configured).isFalse()
            host.call { engine.auth.forget() }
            val stockDenied = assertThrows<ApiError.Http> { runBlocking { repo.setStockKey("td-key-DO-NOT-LEAK-1234") } }
            assertThat(stockDenied.recentAuthRequired).isTrue()
            repo.reauthenticate("", null)
            val stocks = repo.setStockKey("td-key-DO-NOT-LEAK-1234")
            assertThat(stocks.configured).isTrue()
            assertThat(stocks.fingerprint).hasSize(12)
            assertThat(client.get("/v1/market-data/stocks").body.toString()).doesNotContain("DO-NOT-LEAK")
            assertThat(repo.runtime().stocksConfigured).isTrue()
            assertThat(assertThrows<ApiError.Http> { runBlocking { repo.testStockData() } }.status).`as`("no stock provider in this engine").isEqualTo(503)
            assertThat(repo.setStockKey(null).configured).isFalse()

            // Live price streams (D-056): none are configured in this engine.
            assertThat(repo.streams().streams).isEmpty()

            // AI provider setup as the phone screen does it: Gemini preset, key into the key store.
            val gemini = repo.providerTypes().single { it.providerType == "GEMINI" }
            assertThat(gemini.presets["model"]).isEqualTo("gemini-flash-latest")
            assertThat(gemini.keyUrl).isEqualTo("https://aistudio.google.com/apikey")
            val created = repo.createProvider("GEMINI", "Gemini", gemini.presets, "AIza-test-key-DO-NOT-LEAK")
            assertThat(created.credentialConfigured).isTrue()
            assertThat(created.credentialFingerprint).hasSize(12)
            assertThat(created.settings["model"]).isEqualTo("gemini-flash-latest")
            host.call { engine.auth.forget() }
            val keyChange = assertThrows<ApiError.Http> { runBlocking { repo.setProviderKey(created.id, "AIza-other") } }
            assertThat(keyChange.recentAuthRequired).isTrue()
            repo.reauthenticate("", null)
            assertThat(repo.setProviderKey(created.id, null).credentialConfigured).isFalse()
            assertThat(repo.setProviderActive(created.id, false).active).isFalse()
            val renamed = repo.updateProvider(created.id, "Gemini Flash", gemini.presets + ("maxOutputTokens" to "2000"))
            assertThat(renamed.displayName).isEqualTo("Gemini Flash")
            assertThat(renamed.settings["maxOutputTokens"]).isEqualTo("2000")
        }
    }

    @Test
    fun `D-034 a research conversation runs through the app's calls with Gemini web search`() {
        start()
        runBlocking {
            val gemini = repo.providerTypes().single { it.providerType == "GEMINI" }
            repo.createProvider("GEMINI", "Gemini", gemini.presets + ("baseUrl" to ai.url("/").toString().trimEnd('/')), "AIza-test-not-real")
            ai.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.geminiGrounded("They trade Bitcoin and Ethereum.")))
            var d = repo.startConversation("Research the crypto group Chart Champions", "CRYPTO")
            assertThat(d.session.conversation).isTrue()
            assertThat(d.runs.single().ownerMessage).isEqualTo("Research the crypto group Chart Champions")
            assertThat(
                d.runs
                    .single()
                    .sources
                    .map { it.title },
            ).contains("chartchampions.com")

            ai.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.gemini("Use the 4h chart.", "STOP")))
            d = repo.sendResearchMessage(d.session.id, "Focus on Bitcoin")
            assertThat(repo.researchMessageLimit()).`as`("D-040 the default budget allows long messages").isGreaterThan(40_000)
            assertThat(d.runs.map { it.ownerMessage }).containsExactly("Research the crypto group Chart Champions", "Focus on Bitcoin")

            // The app's compile button: review, then compile.
            repo.reviewResearch(d.session.id, true, "Reviewed in the conversation")
            val strategy = JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong("Chart Champions BTC", "4h"))
            ai.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.gemini(strategy, "STOP")))
            d = repo.compileResearch(d.session.id)
            val c = d.compilations.single()
            assertThat(c.status).isEqualTo("COMPILED")
            assertThat(
                repo
                    .strategy(c.strategyId!!)
                    .value()
                    .strategy.name,
            ).isEqualTo("Chart Champions BTC")
            assertThat(
                repo
                    .research()
                    .value()
                    .single()
                    .conversation,
            ).isTrue()
        }
    }

    @Test
    fun `D-045 the owner's Chart Champions research arrives as a labelled plan and backtests per setup`() {
        start()
        runBlocking {
            val reply = javaClass.getResource("/research/chart_champions_plan_v1_reply.md")!!.readText()
            val r = repo.importStrategy(reply)
            assertThat(r.strategy.status).`as`(r.validation?.issues.toString()).isEqualTo("VALIDATED")
            val notes = r.importNotes!!
            assertThat(notes.readback).hasSizeGreaterThan(15)
            assertThat(notes.readback).allMatch { Regex("""^\[(Published|Legacy|Observed|Proposed|Approximation)]""").containsMatchIn(it) }
            assertThat(notes.stillMissing).anyMatch { it.startsWith("Opening range breakout") && it.contains("not applicable") }
            val detail = repo.strategy(r.strategy.id).value()
            assertThat(detail.plan!!.setups.map { it.id }).containsExactly("SFP", "CCV", "CC_FIB", "EMA_SWING")
            assertThat(detail.explanation).contains("Trading plan with 4 setup(s)").contains("Setup 2: CCV value-area rotation")
            val b = repo.runBacktest(r.strategy.id, "2026-02-01T00:00:00Z", "2026-06-22T00:00:00Z", "100000")
            val done = repo.backtests(r.strategy.id).first { it.id == b.id }
            assertThat(done.status).`as`(done.error ?: "").isEqualTo("COMPLETED")
            val setups = done.metrics!!["setups"] as JsonArray
            assertThat(setups.map { it.jsonObject["id"]!!.jsonPrimitive.content }).containsExactly("SFP", "CCV", "CC_FIB", "EMA_SWING")
            println("CC plan v1 on demo data: " + setups.joinToString { it.jsonObject.let { s -> "${s["id"]} trades=${s["trades"]} win=${s["winRatePercent"]} net=${s["netPnl"]}" } })
        }
    }

    @Test
    fun `D-047 the owner's research as plan v2 uses chart-level stops, targets and timeframes`() {
        start()
        runBlocking {
            val reply = javaClass.getResource("/research/chart_champions_plan_v2_reply.md")!!.readText()
            val r = repo.importStrategy(reply)
            assertThat(r.strategy.status).`as`(r.validation?.issues.toString()).isEqualTo("VALIDATED")
            assertThat(r.importNotes!!.readback).allMatch { Regex("""^\[(Published|Legacy|Observed|Proposed|Approximation)]""").containsMatchIn(it) }
            assertThat(r.importNotes!!.readback).noneMatch { it.startsWith("[Approximation]") }
            val detail = repo.strategy(r.strategy.id).value()
            assertThat(detail.plan!!.setups.map { it.id }).containsExactly("SFP", "FAILED_AUCTION", "CCV", "CC_FIB", "EMA_SWING")
            assertThat(detail.explanation)
                .contains("Stop below the signal candle's low plus 0.1%")
                .contains("take profit 50% at the volume-profile point of control of the previous day")
                .contains("decides on 4-hour closes")
                .contains("at least 2 of:")
            val b = repo.runBacktest(r.strategy.id, "2026-02-01T00:00:00Z", "2026-06-22T00:00:00Z", "100000")
            val done = repo.backtests(r.strategy.id).first { it.id == b.id }
            assertThat(done.status).`as`(done.error ?: "").isEqualTo("COMPLETED")
            val setups = done.metrics!!["setups"] as JsonArray
            println("CC plan v2 on demo data: " + setups.joinToString { it.jsonObject.let { s -> "${s["id"]} trades=${s["trades"]} net=${s["netPnl"]}" } })
        }
    }

    @Test
    fun `D-048 the tuned plan v3 imports with both setups able to hold a position`() {
        start()
        runBlocking {
            val reply = javaClass.getResource("/research/chart_champions_plan_v3.md")!!.readText()
            val r = repo.importStrategy(reply)
            assertThat(r.strategy.status).`as`(r.validation?.issues.toString()).isEqualTo("VALIDATED")
            val detail = repo.strategy(r.strategy.id).value()
            assertThat(detail.plan!!.setups.map { it.id }).containsExactly("CC_FIB", "EMA_SWING")
            assertThat(detail.plan!!.conflictPolicy).isEqualTo("STACK")
            assertThat(detail.explanation).contains("at least 2 of:").contains("decides on 4-hour closes")
            val b = repo.runBacktest(r.strategy.id, "2026-02-01T00:00:00Z", "2026-06-22T00:00:00Z", "100000")
            val done = repo.backtests(r.strategy.id).first { it.id == b.id }
            assertThat(done.status).`as`(done.error ?: "").isEqualTo("COMPLETED")
        }
    }

    @Test
    fun `D-049 built-in plans are listed, validate when added and are marked as added`() {
        start()
        runBlocking {
            val lib = repo.library()
            assertThat(lib.map { it.id }).containsExactly("btc-trend-core", "btc-daily-trend-dip-rip", "chart-champions-v3", "spy-trend-core", "index-dip-score", "large-cap-dip-score")
            assertThat(lib.filter { it.assetClass == "CRYPTO" }).allMatch { it.results.size == 12 }
            assertThat(lib.filter { it.assetClass == "US_EQUITY" }.map { it.id }).containsExactly("spy-trend-core", "index-dip-score", "large-cap-dip-score")
            assertThat(lib.filter { it.assetClass == "US_EQUITY" }).allMatch { it.results.size == 15 }
            assertThat(lib).allMatch { it.strategyId == null }
            lib.forEach { p ->
                val r = repo.addLibraryPlan(p.id)
                val errors =
                    r.validation
                        ?.issues
                        .orEmpty()
                        .filter { it.severity == "ERROR" }
                // The demo replay data has only a few stock symbols; with live stock data these plans validate.
                if (p.id == "index-dip-score" || p.id == "large-cap-dip-score") {
                    assertThat(errors.map { it.code }.toSet()).`as`(errors.toString()).isSubsetOf(setOf("NO_MARKET_DATA"))
                } else {
                    assertThat(r.strategy.status).`as`("${p.id}: ${r.validation?.issues}").isEqualTo("VALIDATED")
                }
                assertThat(r.strategy.name).isEqualTo(p.name)
            }
            val after = repo.library()
            assertThat(after).allMatch { it.strategyId != null }
            val dip = after.first { it.id == "btc-daily-trend-dip-rip" }
            assertThat(dip.timeframe).isEqualTo("1d")
            assertThat(repo.strategy(dip.strategyId!!).value().explanation).contains("RSI")
            val b = repo.runBacktest(dip.strategyId!!, "2026-02-01T00:00:00Z", "2026-06-22T00:00:00Z", "10000")
            val done = repo.backtests(dip.strategyId!!).first { it.id == b.id }
            assertThat(done.status).`as`(done.error ?: "").isEqualTo("COMPLETED")
            assertThat(runCatching { repo.addLibraryPlan("nope") }.isFailure).isTrue()
        }
    }

    @Test
    fun `D-044 the futures data test reports when no live source is available`() {
        start()
        runBlocking {
            val t = repo.testFuturesData()
            assertThat(t.source).isEqualTo("NONE")
            assertThat(t.status).isEqualTo("FAILED")
            assertThat(t.detail).contains("No live futures data source")
        }
    }

    @Test
    fun `D-043 a pasted AI reply keeps its readback and research notes with the strategy`() {
        start()
        runBlocking {
            val json = JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong("Noted", "1m"))
            val reply =
                "Here is the strategy.\n```json\n$json\n```\nRULE READBACK\n- Buy every bar.\nFURTHER RESEARCH\n- Stop: vague -> 20% (source: notes)\nSTILL MISSING\n- Short side: not described"
            val r = repo.importStrategy(reply)
            assertThat(r.importNotes!!.readback).containsExactly("Buy every bar.")
            val detail = repo.strategy(r.strategy.id).value()
            assertThat(detail.importNotes!!.furtherResearch).containsExactly("Stop: vague -> 20% (source: notes)")
            assertThat(detail.importNotes!!.stillMissing).containsExactly("Short side: not described")
            assertThat(detail.importNotes!!.other).isEqualTo("Here is the strategy.")
            val plain = repo.importStrategy(json)
            assertThat(plain.importNotes).isNull()
            assertThat(repo.strategy(plain.strategy.id).value().importNotes).isNull()
        }
    }

    @Test
    fun `D-037 scorecards and a better strategy built from them through the app's calls`() {
        start()
        runBlocking {
            suspend fun eligible(name: String): String {
                val id = repo.importStrategy(JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong(name, "1m"))).strategy.id
                repo.runBacktest(id, "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z", "100000")
                return id
            }
            val a = eligible("Scored A")
            eligible("Scored B")
            val cards = repo.scorecards("CRYPTO")
            assertThat(cards.map { it.strategy.name }).containsExactlyInAnyOrder("Scored A", "Scored B")
            val one = repo.scorecard(a)
            assertThat(one.live.closedTrades).isZero()
            assertThat(one.backtest!!.trades).isNotNull()
            assertThat(one.sampleWarning).isNotNull()

            val gemini = repo.providerTypes().single { it.providerType == "GEMINI" }
            repo.createProvider("GEMINI", "Gemini", gemini.presets + ("baseUrl" to ai.url("/").toString().trimEnd('/')), "AIza-test-not-real")
            ai.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.gemini("Combine the entries of A with the exits of B.", "STOP")))
            val d = repo.buildBetterStrategy("CRYPTO")
            assertThat(d.session.conversation).isTrue()
            assertThat(d.session.title).isEqualTo("Better crypto strategy from my results")
            assertThat(d.runs.single().ownerMessage).contains("Scored A").contains("Scored B")
            assertThat(d.runs.single().status).isEqualTo("SUCCEEDED")
        }
    }

    @Test
    fun `D-038 candles with trade markers and the equity curve through the app's calls`() {
        start()
        runBlocking {
            val p = repo.createPortfolio("Charts", "100000")
            advance(60)
            repo.placeOrder(
                app.strategyforge.android.core.data
                    .OrderDraft(p.id, "BTC-USD", "BUY", "MARKET", "0.1"),
            )
            advance(30)
            val c = repo.candles("BTC-USD", "1m", 40, portfolioId = p.id)
            assertThat(c.bars).isNotEmpty().hasSizeLessThanOrEqualTo(40)
            assertThat(
                c.bars
                    .first()
                    .h
                    .toBigDecimal(),
            ).isGreaterThanOrEqualTo(
                c.bars
                    .first()
                    .l
                    .toBigDecimal(),
            )
            assertThat(c.trades.single().side).isEqualTo("BUY")
            val eq = repo.equityChart(p.id, "1D")
            assertThat(eq.points.size).isGreaterThanOrEqualTo(2)
            assertThat(eq.range).isEqualTo("1D")
            val bad = assertThrows<ApiError.Http> { runBlocking { repo.equityChart(p.id, "5Y") } }
            assertThat(bad.code).isEqualTo("invalid-range")
        }
    }

    @Test
    fun `D-042 simulated short selling is turned on for a portfolio through the app's calls`() {
        start()
        runBlocking {
            val p = repo.createPortfolio("Shorts", "100000")
            assertThat(p.shortingEnabled).isFalse()
            val refused = assertThrows<ApiError.Http> { runBlocking { repo.setShorting(p.id, true) } }
            assertThat(refused.message).contains("Confirm it's you")
            host.call { engine.auth.confirmed() }
            assertThat(repo.setShorting(p.id, true).shortingEnabled).isTrue()
            assertThat(repo.setShorting(p.id, false).shortingEnabled).isFalse()
        }
    }

    @Test
    fun `D-051 strategies run in numbered slots, one owner per symbol and portfolio, and replacing a slot asks keep or close`() {
        start()
        runBlocking {
            val p = repo.createPortfolio("Slots", "100000")

            suspend fun eligible(name: String): String {
                val id = repo.importStrategy(JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong(name, "1m"))).strategy.id
                repo.runBacktest(id, "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z", "100000")
                return id
            }
            val a = eligible("First crypto")
            val b = eligible("Second crypto")
            assertThat(repo.activate(a, p.id, "40", false, null).slot).isEqualTo(1)
            val slots = repo.slots()
            assertThat(slots.count { it.assetClass == "CRYPTO" }).isEqualTo(10)
            assertThat(slots.count { it.assetClass == "US_EQUITY" }).isEqualTo(10)
            assertThat(slots.single { it.assetClass == "CRYPTO" && it.number == 1 }.strategy!!.name).isEqualTo("First crypto")
            assertThat(slots.filter { it.assetClass == "US_EQUITY" }.all { it.strategy == null }).isTrue()

            // Both trade BTC-USD: not in the same portfolio, but in a portfolio of its own it takes slot 2.
            val shared = assertThrows<ApiError.Http> { runBlocking { repo.activate(b, p.id, "40", false, null) } }
            assertThat(shared.code).isEqualTo("symbol-shared")
            val own = repo.createPortfolio("Crypto slot 2", "10000")
            assertThat(repo.activate(b, own.id, "100", false, null).slot).isEqualTo(2)
            assertThat(repo.slots().filter { it.assetClass == "CRYPTO" && it.strategy != null }.map { it.number }).containsExactly(1, 2)

            // Choosing an occupied slot replaces its strategy after asking.
            val c = eligible("Third crypto")
            val asked = assertThrows<ApiError.Http> { runBlocking { repo.activate(c, p.id, "40", false, null, slot = 1) } }
            assertThat(asked.code).isEqualTo("slot-occupied")
            assertThat(asked.properties!!["currentStrategyName"]!!.jsonPrimitive.content).isEqualTo("First crypto")
            val switched = repo.activate(c, p.id, "40", false, null, "KEEP", slot = 1)
            assertThat(switched.replacedStrategyName).isEqualTo("First crypto")
            assertThat(switched.positions).isEqualTo("KEEP")
            assertThat(
                repo
                    .slots()
                    .single { it.assetClass == "CRYPTO" && it.number == 1 }
                    .strategy!!
                    .id,
            ).isEqualTo(c)
        }
    }

    @Test
    fun `unknown routes and engine errors come back as problem documents`() {
        start()
        runBlocking {
            val missing = assertThrows<ApiError.Http> { runBlocking { client.get("/v1/portfolios/00000000-0000-0000-0000-000000000000/summary") } }
            assertThat(missing.status).isEqualTo(404)
            val invalid = assertThrows<ApiError.Http> { runBlocking { repo.placeOrder(OrderDraft("00000000-0000-0000-0000-000000000000", "BTC-USD", "HOLD", "MARKET", "1")) } }
            assertThat(invalid.status).isEqualTo(400)
            val gone = assertThrows<ApiError.Http> { runBlocking { client.get("/v1/auth/me") } }
            assertThat(gone.status).isEqualTo(404)
        }
    }

    @Test
    fun `TSX plans run end to end through the app's repository`() {
        start(
            app.strategyforge.engine.support
                .MutableClock(java.time.Instant.parse("2026-08-03T21:00:00Z")),
            app.strategyforge.engine.tsx.SyntheticTsx.provider,
        )
        runBlocking {
            val catalog = repo.tsxCatalog()
            assertThat(catalog.plans.map { it.id }).contains("tsx-dividend-growth-momentum", "tsx-momentum-rotation")
            assertThat(
                catalog.plans
                    .first()
                    .research.valueDrip,
            ).hasSize(
                catalog.plans
                    .first()
                    .research.months.size,
            )
            assertThat(catalog.benchmarks.keys).contains("XIC", "VDY")
            assertThat(catalog.names["RY"]).isNotBlank()
            assertThat(repo.tsxData().latestDay).isNull()
            val refresh = repo.refreshTsxData()
            assertThat(refresh.started).isTrue()
            assertThat(repo.tsxData().latestDay).isEqualTo("2026-08-03")
            val run = repo.startTsxRun("tsx-dividend-growth-momentum", null, "10000", drip = true, autonomous = false)
            assertThat(run.slot).isEqualTo(1)
            assertThat(run.pending).isNotEmpty()
            val approved = repo.tsxRunAction(run.id, "approve")
            assertThat(approved.pending).isNull()
            assertThat(approved.holdings).isNotEmpty()
            assertThat(approved.holdings.first().name).isNotBlank()
            assertThat(approved.events.map { it.kind }).contains("BUY")
            assertThat(repo.tsxRuns().single().id).isEqualTo(run.id)
            val bt = repo.startTsxBacktest("tsx-momentum-rotation", "2024-01-01", "10000")
            val done = repo.tsxBacktest(bt.id)
            assertThat(done.status).isEqualTo("COMPLETED")
            assertThat(done.result!!.months).isNotEmpty()
            assertThat(done.result!!.valuePaidOut).hasSize(done.result!!.months.size)
            assertThat(repo.tsxRunAction(run.id, "stop").status).isEqualTo("STOPPED")
            val err = assertThrows<ApiError.Http> { repo.tsxRunAction(run.id, "approve") }
            assertThat(err.code).isEqualTo("nothing-pending")
        }
    }
}
