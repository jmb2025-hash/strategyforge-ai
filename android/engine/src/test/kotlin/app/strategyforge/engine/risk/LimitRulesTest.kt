package app.strategyforge.engine.risk

import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.DataStatus
import app.strategyforge.engine.market.FeedType
import app.strategyforge.engine.market.Instrument
import app.strategyforge.engine.market.QuoteVerification
import app.strategyforge.engine.market.StoredQuote
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.portfolio.Portfolio
import app.strategyforge.engine.portfolio.PortfolioSummary
import app.strategyforge.engine.portfolio.PositionView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** MS-12 and FR-090/FR-091: every defined limit rule blocks, and the strictest level wins. */
class LimitRulesTest {
    private val now = Instant.parse("2026-06-22T14:00:00Z")
    private val instrument =
        Instrument(UUID.randomUUID(), "BTC-USD", AssetClass.CRYPTO, "Bitcoin", "REPLAY", "USD", BigDecimal("0.01"), BigDecimal("0.0001"), BigDecimal("0.0001"), false, true, "SEED", 0)
    private val portfolio =
        Portfolio(UUID.randomUUID(), "P", "PAPER", "ACTIVE", "USD", BigDecimal(100_000), CostModel(), true, null, null, "OK", now, null, 0)
    private val strategyId = UUID.randomUUID()

    private fun b(v: String) = BigDecimal(v)

    private fun quote(
        bid: String? = "59990",
        ask: String? = "60010",
        last: String = "60000",
        age: Long = 5,
    ) = QuoteVerification(
        DataStatus.VERIFIED,
        StoredQuote(instrument.id, bid?.let(::b), ask?.let(::b), b(last), null, null, now.minusSeconds(age), now, "REPLAY", FeedType.REPLAY_SYNTHETIC),
        age,
        "ok",
    )

    private fun metrics(
        equity: String = "100000",
        dayStart: String = "100000",
        peak: String = "100000",
        perMinute: Int = 0,
        perHour: Int = 0,
        today: Int = 0,
        lastForInstrument: Instant? = null,
        losses: Int = 0,
        openPositions: Int = 0,
        instrumentValue: String = "0",
        assetValue: String = "0",
        shortExposure: String = "0",
        adv: String? = "1000000",
        previousClose: String? = "60000",
        pauseAll: Boolean = false,
        preventNew: Boolean = false,
        strategyStatus: String? = null,
        strategyExposure: String = "0",
        strategyPositions: Int = 0,
        strategyToday: Int = 0,
        strategyLosses: Int = 0,
        strategyLimits: StrategyLimitView? = null,
        drift: Long = 0,
    ) = RiskMetrics(
        b(equity),
        b(dayStart),
        b(peak),
        perMinute,
        perHour,
        today,
        lastForInstrument,
        losses,
        openPositions,
        b(instrumentValue),
        b(assetValue),
        b(shortExposure),
        adv?.let(::b),
        previousClose?.let(::b),
        pauseAll,
        preventNew,
        strategyStatus,
        null,
        b(strategyExposure),
        strategyPositions,
        strategyToday,
        strategyLosses,
        strategyLimits,
        drift,
    )

    private fun summary(positions: List<PositionView> = emptyList()) =
        PortfolioSummary(
            portfolio,
            b("100000"),
            BigDecimal.ZERO,
            b("100000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            b("100000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            true,
            positions,
            now,
        )

    private fun ctx(
        limits: RiskLimits,
        m: RiskMetrics = metrics(),
        side: OrderSide = OrderSide.BUY,
        qty: String = "0.1",
        source: OrderSource = OrderSource.MANUAL,
        q: QuoteVerification = quote(),
        levels: List<Pair<RiskLevel, RiskLimits>> = listOf(RiskLevel.GLOBAL to limits),
    ) = RiskContext(
        OrderIntent(portfolio.id, instrument.id, side, OrderType.MARKET, b(qty), null, null, TimeInForce.DAY, source, if (source == OrderSource.MANUAL) null else strategyId, null),
        portfolio,
        summary(),
        instrument,
        q,
        q.quote?.last,
        emptyList(),
        now,
        limits = EffectiveLimits.merge(levels),
        metrics = m,
    )

    private fun RiskRule.outcome(c: RiskContext) = evaluate(c).outcome

    @Test
    fun `MS-12 emergency controls block every source except emergency close`() {
        val rule = EmergencyControlsRule()
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(pauseAll = true)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(pauseAll = true), side = OrderSide.SELL))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(pauseAll = true), side = OrderSide.SELL, source = OrderSource.EMERGENCY_CLOSE))).isEqualTo(RuleOutcome.PASS)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(preventNew = true)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(preventNew = true), side = OrderSide.SELL))).isEqualTo(RuleOutcome.PASS)
        assertThat(rule.blocksReducing).isTrue()
    }

    @Test
    fun `MS-12 strategy status blocks orders from inactive strategies`() {
        val rule = StrategyStatusRule()
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(strategyStatus = "PAUSED"), source = OrderSource.RECOMMENDATION))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(strategyStatus = "ACTIVE_RECOMMENDATION"), source = OrderSource.AUTONOMOUS))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(strategyStatus = "ACTIVE_AUTONOMOUS"), source = OrderSource.AUTONOMOUS))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 system health fails closed on clock drift`() {
        assertThat(SystemHealthRule().outcome(ctx(RiskLimits(), metrics(drift = 60_000)))).isEqualTo(RuleOutcome.UNVERIFIED)
        assertThat(SystemHealthRule().outcome(ctx(RiskLimits(), metrics(drift = 100)))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 symbol allow and deny lists`() {
        val rule = SymbolListRule()
        assertThat(rule.outcome(ctx(RiskLimits(denySymbols = listOf("BTC-USD"))))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(allowSymbols = listOf("ETH-USD"))))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(allowSymbols = listOf("BTC-USD"))))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 trade value absolute and percent of equity`() {
        val rule = TradeValueRule()
        // 0.1 BTC at 60,000 = 6,000.
        assertThat(rule.outcome(ctx(RiskLimits(maxTradeValue = b("5000"))))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxTradePercentOfEquity = b("5"))))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxTradeValue = b("7000"), maxTradePercentOfEquity = b("7"))))).isEqualTo(RuleOutcome.PASS)
        assertThat(rule.outcome(ctx(RiskLimits(maxTradeValue = b("7000")), q = QuoteVerification(DataStatus.STALE, null, null, "stale")))).isEqualTo(RuleOutcome.UNVERIFIED)
    }

    @Test
    fun `MS-12 instrument, asset class and strategy allocation`() {
        val rule = AllocationRule()
        assertThat(rule.outcome(ctx(RiskLimits(maxInstrumentAllocationPercent = b("10")), metrics(instrumentValue = "5000")))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxAssetClassAllocationPercent = mapOf("CRYPTO" to b("10"))), metrics(assetValue = "5000")))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxStrategyAllocationPercent = b("5")), source = OrderSource.RECOMMENDATION))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxInstrumentAllocationPercent = b("10"))))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 open position count at portfolio and strategy level`() {
        val rule = OpenPositionsRule()
        assertThat(rule.outcome(ctx(RiskLimits(maxOpenPositions = 2), metrics(openPositions = 2)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(strategyPositions = 1, strategyLimits = StrategyLimitView(1, 10, null, false)), source = OrderSource.RECOMMENDATION)))
            .isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxOpenPositions = 3), metrics(openPositions = 2)))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 trade frequency per minute, hour, day, strategy and cooldown`() {
        val rule = TradeFrequencyRule()
        assertThat(rule.outcome(ctx(RiskLimits(maxTradesPerMinute = 2), metrics(perMinute = 2)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxTradesPerHour = 5), metrics(perHour = 5)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxTradesPerDay = 9), metrics(today = 9)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(strategyToday = 3, strategyLimits = StrategyLimitView(5, 3, null, false)), source = OrderSource.AUTONOMOUS)))
            .isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(cooldownSeconds = 300), metrics(lastForInstrument = now.minusSeconds(60))))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(cooldownSeconds = 30), metrics(lastForInstrument = now.minusSeconds(60))))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 daily loss, drawdown and loss streaks`() {
        val rule = LossLimitsRule()
        assertThat(rule.outcome(ctx(RiskLimits(maxDailyLossPercent = b("2")), metrics(equity = "97000", dayStart = "100000", peak = "100000")))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxDrawdownPercent = b("10")), metrics(equity = "88000", dayStart = "88000", peak = "100000")))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxConsecutiveLosses = 3), metrics(losses = 3)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(), metrics(strategyLosses = 2, strategyLimits = StrategyLimitView(5, 5, 2, false)), source = OrderSource.AUTONOMOUS)))
            .isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxDailyLossPercent = b("5"), maxDrawdownPercent = b("20"), maxConsecutiveLosses = 3)))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 shorting permission and short exposure`() {
        val rule = ShortExposureRule()
        assertThat(rule.outcome(ctx(RiskLimits(shortingAllowed = false), side = OrderSide.SELL_SHORT))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxShortExposurePercent = b("10")), metrics(shortExposure = "5000"), side = OrderSide.SELL_SHORT))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxShortExposurePercent = b("10")), side = OrderSide.SELL_SHORT))).isEqualTo(RuleOutcome.PASS)
        assertThat(rule.outcome(ctx(RiskLimits(shortingAllowed = false)))).isEqualTo(RuleOutcome.NOT_APPLICABLE)
    }

    @Test
    fun `MS-12 quote age, spread, liquidity and abnormal price moves`() {
        val rule = MarketQualityRule()
        assertThat(rule.outcome(ctx(RiskLimits(maxQuoteAgeSeconds = 10), q = quote(age = 30)))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxSpreadPercent = b("0.01"))))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxParticipationPercent = b("1")), metrics(adv = "5")))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxParticipationPercent = b("1")), metrics(adv = null)))).isEqualTo(RuleOutcome.UNVERIFIED)
        assertThat(rule.outcome(ctx(RiskLimits(maxPriceDeviationPercent = b("5")), metrics(previousClose = "50000")))).isEqualTo(RuleOutcome.FAIL)
        assertThat(rule.outcome(ctx(RiskLimits(maxPriceDeviationPercent = b("5")), metrics(previousClose = null)))).isEqualTo(RuleOutcome.UNVERIFIED)
        assertThat(rule.outcome(ctx(RiskLimits(maxQuoteAgeSeconds = 60, maxSpreadPercent = b("1"), maxParticipationPercent = b("10"), maxPriceDeviationPercent = b("15"))))).isEqualTo(RuleOutcome.PASS)
    }

    @Test
    fun `MS-12 missing limits or metrics fail closed`() {
        val c = RiskContext(ctx(RiskLimits()).intent, portfolio, summary(), instrument, quote(), b("60000"), emptyList(), now)
        listOf(EmergencyControlsRule(), TradeValueRule(), AllocationRule(), LossLimitsRule(), MarketQualityRule()).forEach {
            assertThat(it.outcome(c)).`as`(it.name).isEqualTo(RuleOutcome.UNVERIFIED)
        }
    }

    @Test
    fun `FR-091 the strictest of global, portfolio and strategy limits wins`() {
        val merged =
            EffectiveLimits.merge(
                listOf(
                    RiskLevel.GLOBAL to RiskLimits(maxTradeValue = b("10000"), maxOpenPositions = 10, shortingAllowed = true, allowSymbols = listOf("BTC-USD", "ETH-USD")),
                    RiskLevel.PORTFOLIO to RiskLimits(maxTradeValue = b("5000"), maxOpenPositions = 20, denySymbols = listOf("SOL-USD")),
                    RiskLevel.STRATEGY to RiskLimits(maxTradeValue = b("8000"), shortingAllowed = false, allowSymbols = listOf("BTC-USD")),
                ),
            )
        assertThat(merged.maxTradeValue).isEqualTo(Limit(b("5000"), RiskLevel.PORTFOLIO))
        assertThat(merged.maxOpenPositions).isEqualTo(Limit(10, RiskLevel.GLOBAL))
        assertThat(merged.shortingAllowed).isEqualTo(Limit(false, RiskLevel.STRATEGY))
        assertThat(merged.allowSymbols!!.value).containsExactly("BTC-USD")
        assertThat(merged.denySymbols).containsExactly("SOL-USD")
        // The block explains which level supplied the limit.
        val r = TradeValueRule().evaluate(ctx(RiskLimits(), levels = listOf(RiskLevel.GLOBAL to RiskLimits(maxTradeValue = b("10000")), RiskLevel.PORTFOLIO to RiskLimits(maxTradeValue = b("5000")))))
        assertThat(r.outcome).isEqualTo(RuleOutcome.FAIL)
        assertThat(r.level).isEqualTo(RiskLevel.PORTFOLIO)
    }
}
