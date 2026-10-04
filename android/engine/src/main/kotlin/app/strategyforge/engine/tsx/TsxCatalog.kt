package app.strategyforge.engine.tsx

import app.strategyforge.engine.common.JacksonCanonical
import com.fasterxml.jackson.databind.JsonNode

/** The bundled TSX universe and built-in portfolio plans with their research results (D-054, D-055). */
object TsxCatalog {
    private fun read(path: String): JsonNode = JacksonCanonical.mapper.readTree(TsxCatalog::class.java.getResource(path)?.readText() ?: error("Missing resource $path"))

    val listings: List<TsxListing> by lazy {
        read("/tsx/universe.json").path("listings").map { TsxListing(it.path("symbol").asText(), it.path("name").asText(), it.path("sector").asText(), it.path("type").asText()) }
    }

    /** The bundled plans file as stored: plans with rules and research, plus benchmarks. */
    val planNodes: JsonNode by lazy { read("/tsx/plans.json") }

    val plans: List<PortfolioPlan> by lazy { planNodes.path("plans").map { PortfolioPlan.from(it) } }

    /** Research results per plan id, as stored (months, value series, metrics). */
    val research: Map<String, JsonNode> by lazy { planNodes.path("plans").associate { it.path("id").asText() to it.path("research") } }

    val benchmarks: JsonNode by lazy { planNodes.path("benchmarks") }

    fun plan(id: String): PortfolioPlan? = plans.firstOrNull { it.id == id }

    fun listing(symbol: String): TsxListing? = listings.firstOrNull { it.symbol == symbol }
}
