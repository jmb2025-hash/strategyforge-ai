package app.strategyforge.engine.market

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** Ported from the Version 1 MarketDataIT. */
class MarketDataTest {
    private val e = TestEngine.create()

    @Test
    fun `FR-020 instrument master contains US equities and allowlisted crypto only`() {
        val all = e.instruments.list(null, null, true)
        assertThat(all.map { it.symbol }).contains("AAPL", "SPY", "BTC-USD", "ETH-USD")
        assertThat(all.map { it.assetClass }.toSet()).containsOnly(AssetClass.US_EQUITY, AssetClass.CRYPTO)
        assertThat(runCatching { e.instruments.add("PEPE-USD") }.exceptionOrNull()).isInstanceOf(EngineException::class.java).hasMessageContaining("allowlisted")
        assertThat((runCatching { e.instruments.add("ZZZZ") }.exceptionOrNull() as EngineException).code).isEqualTo("instrument-not-verified")
        assertThat(e.instruments.list("appl", null, true).map { it.symbol }).containsExactly("AAPL")
    }

    @Test
    fun `FR-023 MS-09 quotes carry feed type, exact timestamp and provider in demo mode`() {
        val q = e.market.refreshQuote(e.instruments.bySymbol("AAPL")).quote!!
        assertThat(q.feedType).isEqualTo(FeedType.REPLAY_SYNTHETIC)
        assertThat(q.provider).isEqualTo("REPLAY")
        assertThat(e.marketMode()).isEqualTo(MarketMode.DEMO)
        assertThat(e.marketClock.mode()).isEqualTo(ClockMode.REPLAY)
        assertThat(
            e.sources
                .active()
                .provider
                .capabilities()[Capability.REALTIME_EQUITY],
        ).isEqualTo(CapabilityState.UNSUPPORTED)
    }

    @Test
    fun `FR-024 MS-17 out-of-order, clock-skewed and malformed data are rejected and block verification`() {
        val jpm = e.instruments.bySymbol("JPM")
        val now = e.marketClock.now()
        val data = e.market
        assertThat(data.storeQuote(jpm, QuoteData(BigDecimal("100.00"), BigDecimal("100.02"), BigDecimal("100.01"), null, null, now.minusSeconds(5), FeedType.REPLAY_SYNTHETIC), "REPLAY", now).status).isEqualTo(DataStatus.VERIFIED)
        assertThat(data.storeQuote(jpm, QuoteData(null, null, BigDecimal("99"), null, null, now.minusSeconds(60), FeedType.REPLAY_SYNTHETIC), "REPLAY", now).status).isEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(data.verifyQuote(jpm, 600, now).status).isEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(data.storeQuote(jpm, QuoteData(null, null, BigDecimal("101"), null, null, now.plusSeconds(120), FeedType.REPLAY_SYNTHETIC), "REPLAY", now).status).isEqualTo(DataStatus.CLOCK_SKEW)
        assertThat(data.storeQuote(jpm, QuoteData(BigDecimal("101"), BigDecimal("100"), BigDecimal("100.5"), null, null, now, FeedType.REPLAY_SYNTHETIC), "REPLAY", now).status).isEqualTo(DataStatus.MALFORMED)
        // A newer valid quote clears the flag.
        assertThat(data.storeQuote(jpm, QuoteData(null, null, BigDecimal("100.50"), null, null, now, FeedType.REPLAY_SYNTHETIC), "REPLAY", now).status).isEqualTo(DataStatus.VERIFIED)
        assertThat(data.verifyQuote(jpm, 1, now.plusSeconds(30)).status).isEqualTo(DataStatus.STALE)

        val outOfOrder =
            listOf(
                CandleData(Instant.parse("2026-06-22T14:30:00Z"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE),
                CandleData(Instant.parse("2026-06-22T13:30:00Z"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE),
            )
        val bac = e.instruments.bySymbol("BAC")
        assertThat(data.storeCandles(bac, Timeframe.H1, outOfOrder, "REPLAY", Instant.parse("2026-06-23T00:00:00Z"))).isEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(data.flagFor(bac.id, "1h")!!.first).isEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(data.verifyCandles(bac, Timeframe.H1, 3).status).isNotEqualTo(DataStatus.VERIFIED)
        assertThat(e.db.sql("select count(*) from audit_events where action in ('QUOTE_REJECTED','CANDLES_REJECTED')").long()).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `FR-022 FR-054 candles aggregate from finer bars and documented gaps are reported`() {
        val msft = e.instruments.bySymbol("MSFT")
        val four = e.market.candles(msft, Timeframe.H4, Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-19T00:00:00Z"))
        assertThat(four.sourceTimeframe).isEqualTo(Timeframe.H1)
        assertThat(four.bars).hasSize(8) // two session-aligned 4h bars per trading day
        assertThat(BarSchedule.closeTime(AssetClass.US_EQUITY, Timeframe.H4, four.bars[0].openTime)).isEqualTo(Instant.parse("2026-06-15T17:30:00Z"))
        assertThat(BarSchedule.closeTime(AssetClass.US_EQUITY, Timeframe.H4, four.bars[1].openTime)).isEqualTo(Instant.parse("2026-06-15T20:00:00Z"))
        val xom = e.instruments.bySymbol("XOM")
        val gaps = e.market.candles(xom, Timeframe.H1, Instant.parse("2026-03-10T00:00:00Z"), Instant.parse("2026-03-13T00:00:00Z"))
        assertThat(gaps.status).isEqualTo(DataStatus.GAPS)
        assertThat(gaps.gaps).contains(Instant.parse("2026-03-11T14:30:00Z"), Instant.parse("2026-03-11T15:30:00Z"), Instant.parse("2026-03-11T16:30:00Z"))
        assertThat(e.market.verifyCandles(xom, Timeframe.H1, 5, Instant.parse("2026-03-11T19:00:00Z")).status).isEqualTo(DataStatus.GAPS)
        val ok = e.market.verifyCandles(msft, Timeframe.H1, 50)
        assertThat(ok.status).`as`(ok.detail).isEqualTo(DataStatus.VERIFIED)
        assertThat(e.market.verifyCandles(msft, Timeframe.H1, 5000).status).isEqualTo(DataStatus.INSUFFICIENT_HISTORY)
    }

    @Test
    fun `FR-021 watchlists and price alerts notify through the inbox with a redacted lock-screen title`() {
        val wl = e.watchlists.create("Core", listOf("AAPL", "BTC-USD"))
        assertThat(wl.items.map { it.symbol }).containsExactly("AAPL", "BTC-USD")
        val btc =
            e.market
                .refreshQuote(e.instruments.bySymbol("BTC-USD"))
                .quote!!
                .last
        val alert = e.alerts.create("BTC-USD", "ABOVE", btc.multiply(BigDecimal("0.95")).setScale(2, RoundingMode.DOWN), null)
        var shown = 0
        e.notifications.listener = { shown++ }
        e.advance(1)
        val a = e.alerts.list("TRIGGERED").first { it.id == alert.id }
        assertThat(a.triggeredPrice).isNotNull()
        val n = e.notifications.list(100, false).first { it.category == "PRICE_ALERT" && it.entityId == alert.id.toString() }
        assertThat(n.redactedTitle).isEqualTo("Price alert").doesNotContain("BTC")
        assertThat(shown).isEqualTo(1)
    }

    @Test
    fun `FR-025 corporate actions and FX are available from replay with coverage`() {
        val nvda = e.instruments.bySymbol("NVDA")
        assertThat(e.corporateActions.covered(nvda, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))).isTrue()
        assertThat(e.corporateActions.actions(nvda.id, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")).map { it.type }).contains(CorporateActionType.SPLIT)
        assertThat(e.market.refreshFx()).isNotNull()
        assertThat(e.market.latestFx()!!.asOf).isNotNull()
        assertThat(e.market.refreshFx("USD", "EUR")).isNull()
    }

    @Test
    fun `FR-112 replay clock advances deterministically and cannot rewind after orders exist`() {
        val before = e.marketClock.now()
        e.advance(2)
        assertThat(e.marketClock.now()).isEqualTo(before.plusSeconds(120))
        // The replay position survives an engine restart on the same database.
        e.replay.setTime(before)
        assertThat(e.marketClock.now()).isEqualTo(before)
    }

    @Test
    fun `D-027 market mode is persisted and live mode without a configured source is explicit`() {
        e.setMarketMode(MarketMode.LIVE)
        assertThat(e.marketClock.mode()).isEqualTo(ClockMode.LIVE)
        val v = e.market.refreshQuote(e.instruments.bySymbol("ETH-USD"))
        assertThat(v.status).isEqualTo(DataStatus.UNSUPPORTED)
        assertThat(v.detail).contains("No live crypto data source")
        assertThat(e.db.sql("select value from settings where key = 'market_mode'").single { it.string("value") }).isEqualTo("LIVE")
        e.setMarketMode(MarketMode.DEMO)
        assertThat(e.marketClock.mode()).isEqualTo(ClockMode.REPLAY)
    }
}
