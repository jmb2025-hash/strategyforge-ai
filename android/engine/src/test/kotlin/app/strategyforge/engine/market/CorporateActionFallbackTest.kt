package app.strategyforge.engine.market

import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** D-077 splits and dividends come from a second source when the stock data source has none. */
class CorporateActionFallbackTest {
    private val e = TestEngine.create()
    private val msft = e.instruments.bySymbol("MSFT")

    /** A stock source without corporate actions, like Twelve Data's free plan. */
    private val noActions =
        object : MarketDataProvider by e.replayProvider {
            override val type: ProviderType get() = ProviderType.TWELVE_DATA

            override fun corporateActions(
                symbol: String,
                assetClass: AssetClass,
                from: LocalDate,
                to: LocalDate,
            ): ProviderResult<List<CorporateActionData>> = ProviderResult.Failed(FailureKind.UNAVAILABLE, "This endpoint is available starting with the Grow plan")
        }

    private val sources =
        MarketSources(e.marketClock, e.replayProvider, { Instant.parse("2026-01-01T00:00:00Z") }, { null }, { noActions }).also { it.use(MarketMode.LIVE) }

    private val split = CorporateActionData(CorporateActionType.SPLIT, LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-02"), BigDecimal("4"), BigDecimal("1"), null)

    private fun directory(actions: List<CorporateActionData>?) =
        object : SymbolDirectory {
            override fun search(query: String) = emptyList<SymbolMatch>()

            override fun snapshot(
                symbol: String,
                range: String,
            ): SymbolSnapshot? = null

            override fun corporateActions(
                symbol: String,
                from: LocalDate,
                to: LocalDate,
            ) = actions
        }

    @Test
    fun `the second source covers the range and its splits are stored`() {
        val svc = CorporateActionService(e.db, sources, e.audit, Clock.systemUTC()) { directory(listOf(split)) }
        assertThat(svc.covered(msft, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"))).isTrue()
        assertThat(svc.coverage(msft.id)!!.provider).isEqualTo("YAHOO")
        assertThat(svc.actions(msft.id, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01")).single().type).isEqualTo(CorporateActionType.SPLIT)
    }

    @Test
    fun `without any source the range is not covered (Manual Review Required)`() {
        val svc = CorporateActionService(e.db, sources, e.audit, Clock.systemUTC()) { directory(null) }
        assertThat(svc.covered(msft, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"))).isFalse()
        assertThat(svc.coverage(msft.id)!!.status).isEqualTo(CoverageStatus.UNAVAILABLE)
    }

    @Test
    fun `Yahoo's events become splits and dividends on New York ex-dates`() {
        val json =
            com.fasterxml.jackson.databind.ObjectMapper().readTree(
                """{"chart":{"result":[{"events":{"splits":{"1598880600":{"date":1598880600,"numerator":4.0,"denominator":1.0}},
                   "dividends":{"1581085800":{"amount":0.1925,"date":1581085800}}}}]}}""",
            )
        val a = YahooSymbolDirectory.parseActions(json, LocalDate.parse("2020-01-01"), LocalDate.parse("2020-12-31"))!!
        assertThat(a.map { it.type to it.exDate.toString() }).containsExactly(CorporateActionType.CASH_DIVIDEND to "2020-02-07", CorporateActionType.SPLIT to "2020-08-31")
        assertThat(a.last().ratioNew).isEqualByComparingTo("4")
    }
}
