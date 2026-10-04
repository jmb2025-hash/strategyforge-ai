package app.strategyforge.engine.tsx

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/**
 * Research harness, not a regression test: runs the four built-in plans on the research's raw
 * Yahoo files (SF_TSX_RAW: <SYM>.csv with open,high,low,close,volume,adj,date and <SYM>.div.csv)
 * and prints them next to the Python results bundled with each plan. CI does not have the files.
 */
class TsxResearchParityTest {
    @Test
    fun `the Kotlin plan engine reproduces the research results`() {
        val dir = System.getenv("SF_TSX_RAW")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(dir != null, "SF_TSX_RAW not set")
        val series =
            TsxCatalog.listings
                .mapNotNull { l ->
                    val f = File(dir, "${l.symbol}.csv").takeIf { it.exists() } ?: return@mapNotNull null
                    val rows =
                        f
                            .readLines()
                            .drop(1)
                            .map { it.split(',') }
                            .filter { it[3].isNotBlank() }
                    val divs =
                        File(dir, "${l.symbol}.div.csv")
                            .takeIf { it.exists() }
                            ?.readLines()
                            ?.drop(1)
                            ?.filter { it.isNotBlank() }
                            ?.groupBy({ LocalDate.parse(it.split(',')[0].take(10)) }, { it.split(',')[1].toDouble() })
                            ?.mapValues { it.value.sum() }
                            .orEmpty()
                    val days = rows.map { LocalDate.parse(it[6].take(10)) }.filter { !it.isBefore(LocalDate.parse("2014-10-01")) }
                    val keep = rows.filter { !LocalDate.parse(it[6].take(10)).isBefore(LocalDate.parse("2014-10-01")) }
                    l.symbol to TsxSeries(days, keep.map { it[3].toDouble() }.toDoubleArray(), keep.map { it[5].toDouble() }.toDoubleArray(), divs)
                }.toMap()
        val data = TsxData(series, LocalDate.parse("2014-10-01"))
        for (plan in TsxCatalog.plans) {
            val t0 = System.currentTimeMillis()
            val drip = PlanSimulator.run(plan, data, TsxCatalog.listings, LocalDate.parse("2016-10-03"), LocalDate.parse("2026-10-02"), 10000.0, true)
            val cash = PlanSimulator.run(plan, data, TsxCatalog.listings, LocalDate.parse("2016-10-03"), LocalDate.parse("2026-10-02"), 10000.0, false)
            val m = PlanSimulator.metrics(drip.values)
            val r = TsxCatalog.research.getValue(plan.id)
            println(
                "PARITY ${plan.name}: kotlin DRIP ${"%.0f".format(drip.values.last().value)} (cagr ${"%.1f".format(m.cagrPercent)} dd ${"%.1f".format(m.maxDrawdownPercent)}) " +
                    "paid-out ${"%.0f".format(cash.values.last().value)} + ${"%.0f".format(cash.dividends.sumOf { it.amount })} | python DRIP ${r.path("endDrip").asInt()} (cagr ${r.path("perYear").asDouble()} dd ${r.path("maxDrawdown").asDouble()}) " +
                    "paid-out ${r.path("endPaidOut").asInt()} + ${r.path("dividendsTotal").asInt()} | ${System.currentTimeMillis() - t0} ms | last targets ${drip.lastTargets.entries.joinToString { "${it.key}=${"%.3f".format(it.value)}" }}",
            )
        }
    }
}
