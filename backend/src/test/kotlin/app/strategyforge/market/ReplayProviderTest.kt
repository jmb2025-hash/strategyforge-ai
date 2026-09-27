package app.strategyforge.market

import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.market.data.CandleAggregator
import app.strategyforge.market.provider.ReplayFixtures
import app.strategyforge.market.provider.ReplayProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant

class ReplayProviderTest {
    private val provider = ReplayProvider(ReplayFixtures(DefaultResourceLoader(), StrategyForgeProperties()))

    @Test
    fun `NFR-012 FR-022 replay quotes are point in time and labelled synthetic`() {
        val t = Instant.parse("2026-06-22T14:00:30Z")
        val q = (provider.quote("AAPL", AssetClass.US_EQUITY, t) as ProviderResult.Ok).value
        assertThat(q.feedType).isEqualTo(FeedType.REPLAY_SYNTHETIC)
        assertThat(q.exchangeTs).isEqualTo(Instant.parse("2026-06-22T14:00:00Z"))
        assertThat(q.bid!!).isLessThanOrEqualTo(q.last)
        assertThat(q.ask!!).isGreaterThanOrEqualTo(q.last)
        // Determinism: the same market time always yields the same quote.
        assertThat((provider.quote("AAPL", AssetClass.US_EQUITY, t) as ProviderResult.Ok).value).isEqualTo(q)
        // After the close the quote is the final bar close, timestamped at the close.
        val after = (provider.quote("AAPL", AssetClass.US_EQUITY, Instant.parse("2026-06-22T21:00:00Z")) as ProviderResult.Ok).value
        assertThat(after.exchangeTs).isEqualTo(Instant.parse("2026-06-22T20:00:00Z"))
    }

    @Test
    fun `FR-050 replay candles never include bars that have not closed`() {
        val now = Instant.parse("2026-06-22T15:10:00Z")
        val bars = (provider.candles("AAPL", AssetClass.US_EQUITY, Timeframe.H1, Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-23T00:00:00Z"), now) as ProviderResult.Ok).value
        assertThat(bars.map { it.openTime }).containsExactly(Instant.parse("2026-06-22T13:30:00Z"))
        val none = provider.candles("AAPL", AssetClass.US_EQUITY, Timeframe.M5, now.minusSeconds(3600), now, now)
        assertThat(none).isInstanceOf(ProviderResult.Unsupported::class.java)
    }

    @Test
    fun `FR-022 minute bars aggregate exactly into the hourly fixture bars`() {
        val now = Instant.parse("2026-06-23T00:00:00Z")
        val from = Instant.parse("2026-06-22T13:30:00Z")
        val minutes = (provider.candles("MSFT", AssetClass.US_EQUITY, Timeframe.M1, from, now, now) as ProviderResult.Ok).value
        val hourly = (provider.candles("MSFT", AssetClass.US_EQUITY, Timeframe.H1, from, now, now) as ProviderResult.Ok).value
        val (agg, gaps) = CandleAggregator.aggregate(minutes, AssetClass.US_EQUITY, Timeframe.M1, Timeframe.H1, now)
        assertThat(gaps).isEmpty()
        assertThat(agg).hasSize(7)
        agg.zip(hourly).forEach { (a, h) ->
            assertThat(a.openTime).isEqualTo(h.openTime)
            assertThat(a.close).isEqualByComparingTo(h.close)
            assertThat(a.high).isEqualByComparingTo(h.high)
            assertThat(a.volume).isEqualByComparingTo(h.volume)
        }
    }

    @Test
    fun `FR-025 replay supplies synthetic split and dividends and USD CAD rates`() {
        val nvda = (provider.corporateActions("NVDA", AssetClass.US_EQUITY, java.time.LocalDate.parse("2025-06-01"), java.time.LocalDate.parse("2025-06-30")) as ProviderResult.Ok).value
        assertThat(nvda.single().type).isEqualTo(CorporateActionType.SPLIT)
        assertThat(nvda.single().ratioNew).isEqualByComparingTo("4")
        val fx = (provider.fxRate("USD", "CAD", Instant.parse("2026-06-22T14:00:00Z")) as ProviderResult.Ok).value
        assertThat(fx.asOf).isBefore(Instant.parse("2026-06-22T14:00:00Z"))
        assertThat(provider.fxRate("USD", "EUR", Instant.parse("2026-06-22T14:00:00Z"))).isInstanceOf(ProviderResult.Unsupported::class.java)
    }
}
