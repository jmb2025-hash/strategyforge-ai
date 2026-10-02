package app.strategyforge.engine.market

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sin

/**
 * Synthetic perpetual-futures context for demo mode (D-044), derived from the replay candles so it
 * moves with them: delta leans with each candle's body, open interest moves in slow waves and builds
 * with one-sided flow, and funding follows price's premium over its one-day average. It only lets demo
 * strategies exercise these rules; it says nothing about real markets.
 */
class ReplayDerivatives(
    private val replay: MarketDataProvider,
) : DerivativesProvider {
    override val name = "REPLAY_SYNTHETIC"

    override fun contractFor(symbol: String): String? = if (symbol.uppercase().endsWith("-USD")) "SYNTH_$symbol" else null

    override fun history(
        symbol: String,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<DerivativesData> {
        // Hourly bars drive funding and open interest; the strategy's own bars drive delta.
        val warm = Duration.ofDays(2)
        val hourly =
            when (val r = replay.candles(symbol, AssetClass.CRYPTO, Timeframe.H1, from.minus(warm), to, now)) {
                is ProviderResult.Ok -> r.value
                is ProviderResult.Failed -> return r
                is ProviderResult.Unsupported -> return r
            }
        val own =
            if (timeframe == Timeframe.H1) {
                hourly
            } else {
                when (val r = replay.candles(symbol, AssetClass.CRYPTO, timeframe, from, to, now)) {
                    is ProviderResult.Ok -> r.value
                    else -> hourly
                }
            }
        return ProviderResult.Ok(DerivativesData(own.map { DerivPoint(it.openTime, openInterest(it)) }, funding(hourly), own.map { DerivPoint(it.openTime, delta(it)) }, name))
    }

    override fun diagnose(now: Instant) = ProviderTestResult(TestStatus.OK, "Synthetic demo futures data derived from the replay candles")

    companion object {
        fun delta(c: CandleData): Double {
            val range = c.high.subtract(c.low).toDouble()
            if (range <= 0.0) return 0.0
            val lean = (c.close.toDouble() - c.open.toDouble()) / range
            return c.volume.toDouble() * lean.coerceIn(-1.0, 1.0) * 0.6
        }

        /**
         * Open interest for one bar, from its time and its own flow only, so the same bar gets the same
         * value whatever window is loaded: slow waves of positioning, building with one-sided bars.
         */
        fun openInterest(c: CandleData): Double {
            val hours = c.openTime.epochSecond / 3600.0
            val vol = c.volume.toDouble().coerceAtLeast(1.0)
            val oneSided = abs(delta(c)) / vol
            return 10_000.0 * (1 + 0.15 * sin(hours / 29.0) + 0.05 * sin(hours / 7.0)) * (1 + 0.02 * oneSided)
        }

        fun funding(hourly: List<CandleData>): List<DerivPoint> =
            hourly.indices.mapNotNull { i ->
                if (i < 24) return@mapNotNull null
                val avg = (i - 23..i).sumOf { hourly[it].close.toDouble() } / 24
                if (avg <= 0) return@mapNotNull null
                val premium = (hourly[i].close.toDouble() - avg) / avg
                // Applied at the end of the hour it was measured in.
                DerivPoint(hourly[i].openTime.plus(Duration.ofHours(1)), (0.001 + premium * 0.2).coerceIn(-0.03, 0.03))
            }
    }
}
