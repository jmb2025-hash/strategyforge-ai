package app.strategyforge.android.core.model

import kotlinx.serialization.Serializable

// TSX portfolio plans (D-055). These are C$ paper portfolios kept apart from the order engine;
// values are plain numbers because the plan math is done in doubles (see D-055).

@Serializable
data class TsxCatalog(
    val plans: List<TsxPlan> = emptyList(),
    val benchmarks: Map<String, TsxBenchmark> = emptyMap(),
    val slots: Int = 10,
    val names: Map<String, String> = emptyMap(),
)

@Serializable
data class TsxPlan(
    val id: String,
    val name: String,
    val horizon: String = "",
    val rebalance: String = "",
    val summary: String = "",
    val rules: List<String> = emptyList(),
    val research: TsxResearch = TsxResearch(),
)

@Serializable
data class TsxResearch(
    val from: String? = null,
    val to: String? = null,
    val selectionTo: String? = null,
    val startingCash: Double = 10_000.0,
    val months: List<String> = emptyList(),
    val valueDrip: List<Double> = emptyList(),
    val valuePaidOut: List<Double> = emptyList(),
    val dividendsPaid: List<Double> = emptyList(),
    val perYear: Double? = null,
    val selectionPerYear: Double? = null,
    val unseenPerYear: Double? = null,
    val totalPercent: Double? = null,
    val maxDrawdown: Double? = null,
    val worst12m: Double? = null,
    val positive12m: Double? = null,
    val endDrip: Double? = null,
    val endPaidOut: Double? = null,
    val dividendsTotal: Double? = null,
    val dividendsLast12m: Double? = null,
    val trades: Int? = null,
    val years: Map<String, Double> = emptyMap(),
    val dividendYears: Map<String, Double> = emptyMap(),
    val lastTargets: Map<String, Double> = emptyMap(),
)

@Serializable
data class TsxBenchmark(
    val name: String = "",
    val valueDrip: List<Double> = emptyList(),
    val perYear: Double? = null,
    val maxDrawdown: Double? = null,
)

@Serializable
data class TsxDataStatus(
    val listings: Int = 0,
    val cached: Int = 0,
    val latestDay: String? = null,
    val lastRefreshAt: String? = null,
    val refreshing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val error: String? = null,
)

@Serializable
data class TsxRefresh(
    val started: Boolean,
    val status: TsxDataStatus = TsxDataStatus(),
)

@Serializable
data class TsxBacktest(
    val id: String,
    val planId: String,
    val from: String = "",
    val to: String = "",
    val status: String,
    val error: String? = null,
    val result: TsxResearch? = null,
)

@Serializable
data class TsxRun(
    val id: String,
    val planId: String,
    val planName: String,
    val slot: Int,
    val mode: String,
    val drip: Boolean,
    val startingCash: Double,
    val cash: Double,
    val value: Double,
    val dividendsReceived: Double = 0.0,
    val status: String,
    val startDay: String? = null,
    val lastDay: String? = null,
    val pendingDay: String? = null,
    val pending: List<TsxWeight>? = null,
    val holdings: List<TsxHolding> = emptyList(),
    val createdAt: String = "",
    val values: List<TsxValue> = emptyList(),
    val monthlyValues: List<TsxMonthValue> = emptyList(),
    val monthlyDividends: List<TsxMonthDividend> = emptyList(),
    val events: List<TsxEvent> = emptyList(),
)

@Serializable
data class TsxWeight(
    val symbol: String,
    val name: String? = null,
    val weight: Double,
)

@Serializable
data class TsxHolding(
    val symbol: String,
    val name: String? = null,
    val shares: Double,
    val price: Double,
    val value: Double,
)

@Serializable
data class TsxValue(
    val day: String,
    val value: Double,
)

@Serializable
data class TsxMonthValue(
    val month: String,
    val value: Double,
)

@Serializable
data class TsxMonthDividend(
    val month: String,
    val amount: Double,
)

@Serializable
data class TsxEvent(
    val day: String,
    val kind: String,
    val symbol: String? = null,
    val shares: Double? = null,
    val price: Double? = null,
    val amount: Double? = null,
)
