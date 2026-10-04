package app.strategyforge.engine.tsx

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate

/** One TSX listing the portfolio plans can hold (D-055). Symbols use Yahoo's form, e.g. REI-UN, CGL-C. */
data class TsxListing(
    val symbol: String,
    val name: String,
    val sector: String,
    /** EQUITY or ETF. */
    val type: String,
) {
    val isStock: Boolean get() = type == "EQUITY"

    /** How the TSX writes it: REI.UN, CGL.C, RCI.B. */
    val display: String get() = symbol.replace('-', '.')
}

/** How often a plan re-weights, or a sleeve re-selects, on the first trading day of each period. */
enum class Cadence {
    MONTHLY,
    QUARTERLY,
    ANNUAL,
    ;

    fun period(d: LocalDate): Int =
        when (this) {
            MONTHLY -> d.year * 100 + d.monthValue
            QUARTERLY -> d.year * 10 + (d.monthValue - 1) / 3 + 1
            ANNUAL -> d.year
        }

    val label: String get() = name.lowercase()
}

/** A selection rule for one sleeve of a portfolio plan. */
sealed interface SleeveRule {
    /**
     * Dividend payers ranked by yield, or by yield and price momentum together (equal percentile
     * ranks), with a minimum yield, a dividend-cut screen, a per-sector cap and an optional trend
     * filter that parks a holding below its moving average in [trendSafe].
     */
    data class Dividend(
        val rank: String,
        val momentumMonths: Int,
        val maxHoldings: Int,
        val sectorCap: Int,
        val minYield: Double,
        val excludeCuts: Boolean,
        val trendSmaDays: Int?,
        val trendSafe: String?,
    ) : SleeveRule

    /** Fixed target weights (fractions summing to at most 1). */
    data class Fixed(
        val weights: Map<String, Double>,
    ) : SleeveRule

    /**
     * The strongest names by total return over [lookbackMonths]: from [universe], or from every TSX
     * stock with at least [minBars] days of history when [universe] is empty. Each pick gets
     * 1/[top]; an unfilled remainder goes to [fillWith], or stays in cash when it is null.
     */
    data class Momentum(
        val universe: List<String>,
        val lookbackMonths: Int,
        val top: Int,
        val sectorCap: Int,
        val minBars: Int,
        val fillWith: String?,
    ) : SleeveRule
}

data class Sleeve(
    val share: Double,
    val select: Cadence,
    val rule: SleeveRule,
)

/** A portfolio plan (D-055): sleeves re-weighted together on [rebalance] days. */
data class PortfolioPlan(
    val id: String,
    val name: String,
    val horizon: String,
    val summary: String,
    val rules: List<String>,
    val rebalance: Cadence,
    val sleeves: List<Sleeve>,
    val cost: Double,
) {
    /** Every symbol a plan could hold besides the stock universe. */
    val namedSymbols: Set<String>
        get() =
            sleeves
                .flatMap { s ->
                    when (val r = s.rule) {
                        is SleeveRule.Fixed -> r.weights.keys
                        is SleeveRule.Momentum -> r.universe + listOfNotNull(r.fillWith)
                        is SleeveRule.Dividend -> listOfNotNull(r.trendSafe)
                    }
                }.toSet()

    companion object {
        fun from(n: JsonNode): PortfolioPlan =
            PortfolioPlan(
                id = n.path("id").asText(),
                name = n.path("name").asText(),
                horizon = n.path("horizon").asText(),
                summary = n.path("summary").asText(),
                rules = n.path("rules").map { it.asText() },
                rebalance = Cadence.valueOf(n.path("rebalance").asText()),
                cost = n.path("cost").asDouble(0.001),
                sleeves =
                    n.path("sleeves").map { s ->
                        val r = s.path("rule")
                        Sleeve(
                            share = s.path("share").asDouble(),
                            select = Cadence.valueOf(s.path("select").asText()),
                            rule =
                                when (r.path("type").asText()) {
                                    "DIVIDEND" ->
                                        SleeveRule.Dividend(
                                            rank = r.path("rank").asText(),
                                            momentumMonths = r.path("momentumMonths").asInt(6),
                                            maxHoldings = r.path("maxHoldings").asInt(),
                                            sectorCap = r.path("sectorCap").asInt(0),
                                            minYield = r.path("minYield").asDouble(0.0),
                                            excludeCuts = r.path("excludeCuts").asBoolean(false),
                                            trendSmaDays = r.path("trendSmaDays").takeIf { it.isInt }?.asInt(),
                                            trendSafe = r.path("trendSafe").takeIf { it.isTextual }?.asText(),
                                        )
                                    "FIXED" -> SleeveRule.Fixed(r.path("weights").properties().associate { (k, v) -> k to v.asDouble() })
                                    "MOMENTUM" ->
                                        SleeveRule.Momentum(
                                            universe = r.path("universe").map { it.asText() },
                                            lookbackMonths = r.path("lookbackMonths").asInt(),
                                            top = r.path("top").asInt(),
                                            sectorCap = r.path("sectorCap").asInt(0),
                                            minBars = r.path("minBars").asInt(260),
                                            fillWith = r.path("fillWith").takeIf { it.isTextual }?.asText(),
                                        )
                                    else -> error("Unknown sleeve rule ${r.path("type").asText()}")
                                },
                        )
                    },
            )
    }
}
