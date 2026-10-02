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

    private fun start() {
        engine =
            host.call {
                Engine(
                    JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
                    fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
                    allowLocalProviderHttp = true,
                    backupDir = { backups },
                )
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
    fun `D-035 activating a second crypto strategy asks keep or close through the app's calls`() {
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
            repo.activate(a, p.id, "40", false, null)
            assertThat(
                repo
                    .slots()
                    .single { it.assetClass == "CRYPTO" }
                    .strategy!!
                    .name,
            ).isEqualTo("First crypto")
            assertThat(repo.slots().single { it.assetClass == "US_EQUITY" }.strategy).isNull()

            val asked = assertThrows<ApiError.Http> { runBlocking { repo.activate(b, p.id, "40", false, null) } }
            assertThat(asked.code).isEqualTo("slot-occupied")
            assertThat(asked.properties!!["currentStrategyName"]!!.jsonPrimitive.content).isEqualTo("First crypto")

            val switched = repo.activate(b, p.id, "40", false, null, "KEEP")
            assertThat(switched.replacedStrategyName).isEqualTo("First crypto")
            assertThat(switched.positions).isEqualTo("KEEP")
            assertThat(
                repo
                    .slots()
                    .single { it.assetClass == "CRYPTO" }
                    .strategy!!
                    .id,
            ).isEqualTo(b)
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
}
