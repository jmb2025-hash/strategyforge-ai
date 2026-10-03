package app.strategyforge.engine.research

import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.Problems
import com.fasterxml.jackson.databind.node.ObjectNode

/** One backtested period shown with a built-in plan. */
data class LibraryResult(
    val period: String,
    val returnPercent: String,
    val maxDrawdownPercent: String,
    val trades: Int,
    val winRatePercent: String,
)

/** A ready-made trading plan shipped with the app (D-049), researched on real market history. */
data class LibraryPlan(
    val id: String,
    val name: String,
    val summary: String,
    val backtest: String,
    val results: List<LibraryResult>,
    val plan: ObjectNode,
) {
    val assetClass: String get() = plan.path("metadata").path("assetClass").asText()
    val timeframe: String get() = plan.path("metadata").path("timeframe").asText()
}

/**
 * The built-in plans (D-049), bundled as resources under /library. Adding one imports its plan like any
 * other strategy file, so it is validated and versioned the same way and can be put in a slot.
 */
object PlanLibrary {
    val IDS = listOf("btc-trend-core", "btc-daily-trend-dip-rip", "chart-champions-v3", "index-dip-score", "spy-trend-core")

    val all: List<LibraryPlan> by lazy { IDS.map(::load) }

    fun get(id: String): LibraryPlan = all.firstOrNull { it.id == id } ?: throw Problems.notFound("Built-in plan", id)

    private fun load(id: String): LibraryPlan {
        val text = PlanLibrary::class.java.getResource("/library/$id.json")?.readText() ?: error("Missing library resource $id")
        val n = JacksonCanonical.mapper.readTree(text)
        return LibraryPlan(
            id = n.path("id").asText(),
            name = n.path("name").asText(),
            summary = n.path("summary").asText(),
            backtest = n.path("backtest").asText(),
            results =
                n.path("results").map { r ->
                    LibraryResult(r.path("period").asText(), r.path("returnPercent").asText(), r.path("maxDrawdownPercent").asText(), r.path("trades").asInt(), r.path("winRatePercent").asText())
                },
            plan = n.path("plan") as ObjectNode,
        )
    }
}
