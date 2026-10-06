package app.strategyforge.engine.api

import app.strategyforge.android.core.api.ApiClient
import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.api.TokenStore
import app.strategyforge.android.core.cache.CachedResource
import app.strategyforge.android.core.cache.MemoryCacheStore
import app.strategyforge.android.core.data.OrderDraft
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.state.toFailure
import app.strategyforge.engine.Engine
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.PricePoint
import app.strategyforge.engine.market.SymbolDirectory
import app.strategyforge.engine.market.SymbolMatch
import app.strategyforge.engine.market.SymbolSnapshot
import app.strategyforge.engine.support.TestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant

/** D-065 the order screen's symbol search, symbol information and field-specific errors through the app's calls. */
class InstrumentSearchTest {
    private val host = EngineHost("sf-instrument-test")
    private lateinit var engine: Engine
    private lateinit var repo: Repository

    /** A stand-in for Yahoo's directory: a known stock, one that can be added, and a listing elsewhere is never returned. */
    private val directory =
        object : SymbolDirectory {
            var searches = 0

            override fun search(query: String): List<SymbolMatch> {
                searches++
                return listOf(
                    SymbolMatch("RKLB", "Rocket Lab Corporation", "EQUITY", "NASDAQ", "Industrials", "Aerospace & Defense"),
                    SymbolMatch("AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "Technology", "Consumer Electronics"),
                    SymbolMatch("BTC-USD", "Bitcoin USD", "CRYPTOCURRENCY", "CCC", null, null),
                    SymbolMatch("DOGE-USD", "Dogecoin USD", "CRYPTOCURRENCY", "CCC", null, null),
                ).filter { it.symbol.contains(query.uppercase()) || it.name.uppercase().contains(query.uppercase()) }
            }

            override fun snapshot(
                symbol: String,
                range: String,
            ) = SymbolSnapshot(
                symbol,
                "$symbol name",
                "USD",
                "NasdaqGS",
                BigDecimal("238.90"),
                BigDecimal("233.95"),
                BigDecimal("240.10"),
                BigDecimal("235.15"),
                BigDecimal("240.10"),
                BigDecimal("164.27"),
                BigDecimal("126384437"),
                Instant.parse("2026-10-05T20:00:00Z"),
                range,
                listOf(PricePoint(Instant.parse("2026-10-02T20:00:00Z"), BigDecimal("230.36")), PricePoint(Instant.parse("2026-10-05T20:00:00Z"), BigDecimal("238.90"))),
            )
        }

    private fun start() {
        engine =
            host.call {
                Engine(
                    JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
                    fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
                    symbolDirectory = { directory },
                )
            }
        val api = host.call { LocalApi(engine) }
        val http = OkHttpClient.Builder().addInterceptor(LocalApiInterceptor(host, api::handle)).build()
        val tokens =
            object : TokenStore {
                override fun token(): String? = null

                override fun save(token: String?) {}
            }
        repo = Repository(ApiClient({ LocalApiInterceptor.BASE_URL }, http, tokens, io = Dispatchers.Unconfined, retryDelaysMs = emptyList()), CachedResource(MemoryCacheStore()), tokens)
    }

    @AfterEach
    fun stop() = host.close()

    @Test
    fun `typing finds known symbols first, then US stocks that can be added, and never other coins`() {
        start()
        runBlocking {
            val btc = repo.searchInstruments("btc")
            assertThat(btc.first().symbol).isEqualTo("BTC-USD")
            assertThat(btc.first().added).isTrue()

            val crypto = repo.searchInstruments("USD", "CRYPTO")
            assertThat(crypto).allMatch { it.assetClass == "CRYPTO" && it.added }
            assertThat(crypto.map { it.symbol }).doesNotContain("DOGE-USD")

            val nv = repo.searchInstruments("rocket")
            val nvda = nv.single { it.symbol == "RKLB" }
            assertThat(nvda.added).isFalse()
            assertThat(nvda.assetClass).isEqualTo("US_EQUITY")
            assertThat(nvda.sector).isEqualTo("Industrials")
            assertThat(nv.map { it.symbol }).doesNotContain("DOGE-USD")

            assertThat(repo.searchInstruments("   ")).isEmpty()
        }
    }

    @Test
    fun `the information screen shows display prices, ranges and the price orders fill from`() {
        start()
        runBlocking {
            val info = repo.instrumentInfo("BTC-USD", "1Y", refresh = true)
            assertThat(info.instrument.symbol).isEqualTo("BTC-USD")
            assertThat(info.price).isEqualTo("238.90")
            assertThat(info.previousClose).isEqualTo("233.95")
            assertThat(info.yearHigh).isEqualTo("240.10")
            assertThat(info.range).isEqualTo("1Y")
            assertThat(info.points).hasSize(2)
            assertThat(info.tradingPrice).isNotNull()
            assertThat(info.tradingSource).isNotBlank()

            // A US stock not added yet still has an information screen.
            val nvda = repo.instrumentInfo("RKLB", "1D")
            assertThat(nvda.instrument.added).isFalse()
            assertThat(nvda.instrument.industry).isEqualTo("Aerospace & Defense")
            assertThat(nvda.tradingPrice).isNull()

            val unknown = assertThrows<ApiError.Http> { runBlocking { repo.instrumentInfo("ZZZZQ", "1M") } }
            assertThat(unknown.code).isEqualTo("unknown-symbol")
            assertThat(unknown.field).isEqualTo("symbol")
            assertThat(assertThrows<ApiError.Http> { runBlocking { repo.instrumentInfo("BTC-USD", "7Y") } }.code).isEqualTo("invalid-range")
        }
    }

    @Test
    fun `order errors name the field they are about`() {
        start()
        runBlocking {
            val p = repo.createPortfolio("Main", "100000")
            val unknown = assertThrows<ApiError.Http> { runBlocking { repo.placeOrder(OrderDraft(p.id, "NOTREAL", "BUY", "MARKET", "1", timeInForce = "DAY")) } }
            assertThat(unknown.code).isEqualTo("unknown-symbol")
            assertThat(unknown.toFailure().field).isEqualTo("symbol")
            assertThat(unknown.detail).contains("NOTREAL").contains("suggestions")

            val qty = assertThrows<ApiError.Http> { runBlocking { repo.placeOrder(OrderDraft(p.id, "BTC-USD", "BUY", "MARKET", "0", timeInForce = "GTC")) } }
            assertThat(qty.field).isEqualTo("quantity")
            val price = assertThrows<ApiError.Http> { runBlocking { repo.placeOrder(OrderDraft(p.id, "BTC-USD", "BUY", "LIMIT", "1", "-5", timeInForce = "GTC")) } }
            assertThat(price.field).isEqualTo("limitPrice")
        }
    }

    @Test
    fun `picking a known symbol keeps it, and a stock the data source cannot confirm is refused at the symbol field`() {
        start()
        runBlocking {
            assertThat(repo.addInstrument("btc-usd").symbol).isEqualTo("BTC-USD")
            // The replay data source (no live stock key) cannot confirm RKLB.
            val e = assertThrows<ApiError.Http> { runBlocking { repo.addInstrument("RKLB") } }
            assertThat(e.field).isEqualTo("symbol")
            assertThat(host.call { engine.instruments.findBySymbol("RKLB") }).isNull()
            assertThat(assertThrows<ApiError.Http> { runBlocking { repo.addInstrument("DOGE-USD") } }.code).isEqualTo("unknown-symbol")
            val stock = host.call { engine.instruments.list(null, AssetClass.US_EQUITY.name, true).first() }
            assertThat(repo.addInstrument(stock.symbol.lowercase()).added).isTrue()
        }
    }

    @Test
    fun `D-066 a dollar amount buys a fractional quantity that costs no more than the amount, and sells are capped at the holding`() {
        start()
        runBlocking {
            val p = repo.createPortfolio("Small", "500")
            // Manual orders follow the default limits (20% per trade); the owner raises them on Risk limits.
            repo.reauthenticate("", null)
            repo.setGlobalLimits(mapOf("maxTradePercent" to "100", "maxInstrumentPercent" to "100", "maxCryptoPercent" to "100"))
            // All of the cash: the quantity is sized so price, buffer and costs fit in 500 USD.
            val buy = repo.placeOrder(OrderDraft(p.id, "BTC-USD", "BUY", "MARKET", "", timeInForce = "GTC", amount = "500"))
            assertThat(buy.rejectionReason).isNull()
            val qty = BigDecimal(buy.quantity)
            assertThat(qty).isGreaterThan(BigDecimal.ZERO).isLessThan(BigDecimal.ONE)
            assertThat(qty.stripTrailingZeros().scale()).isGreaterThan(0)
            host.call { engine.replay.advance(2, 1) }
            val filled = repo.portfolioSummaryNow(p.id)
            assertThat(BigDecimal(filled.cash)).isGreaterThanOrEqualTo(BigDecimal.ZERO)
            val held = BigDecimal(filled.positions.single().quantity)
            assertThat(held).isEqualByComparingTo(qty)

            // Selling more dollars than the position is worth sells the whole position.
            val sell = repo.placeOrder(OrderDraft(p.id, "BTC-USD", "SELL", "MARKET", "", timeInForce = "GTC", amount = "100000"))
            assertThat(BigDecimal(sell.quantity)).isEqualByComparingTo(held)

            val tiny = assertThrows<ApiError.Http> { runBlocking { repo.placeOrder(OrderDraft(p.id, "BTC-USD", "BUY", "MARKET", "", timeInForce = "GTC", amount = "0.000001")) } }
            assertThat(tiny.code).isEqualTo("amount-too-small")
            assertThat(tiny.field).isEqualTo("amount")
            val zero = assertThrows<ApiError.Http> { runBlocking { repo.placeOrder(OrderDraft(p.id, "BTC-USD", "BUY", "MARKET", "", timeInForce = "GTC", amount = "0")) } }
            assertThat(zero.field).isEqualTo("amount")

            // A limit order by amount uses the limit price.
            val limit = repo.placeOrder(OrderDraft(p.id, "SOL-USD", "BUY", "LIMIT", "", "10", timeInForce = "GTC", amount = "50"))
            assertThat(BigDecimal(limit.quantity)).isLessThanOrEqualTo(BigDecimal("5")).isGreaterThan(BigDecimal("4.9"))
        }
    }
}
