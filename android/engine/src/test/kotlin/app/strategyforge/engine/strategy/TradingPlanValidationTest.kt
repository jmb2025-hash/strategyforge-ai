package app.strategyforge.engine.strategy

import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.support.Plans
import app.strategyforge.engine.support.TestEngine
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** D-045 trading plans: a plan with several setups validates, and every setup is checked like a strategy. */
class TradingPlanValidationTest {
    private val e = TestEngine.create()

    private fun tree(s: String) = JacksonCanonical.mapper.readTree(s) as ObjectNode

    private fun validate(s: String) = e.validator.validateDocument(tree(s))

    @Test
    fun `a plan with three setups validates and builds every setup`() {
        val r = validate(Plans.rangePlan())
        assertThat(r.outcome).`as`(r.issues.toString()).isEqualTo(ValidationOutcome.VALIDATED)
        val d = r.definition!!
        assertThat(d.isPlan).isTrue()
        assertThat(d.setups.map { it.id }).containsExactly("SFP_LOW", "SFP_HIGH", "BREAKOUT")
        assertThat(d.direction).isEqualTo(Direction.BOTH)
        assertThat(d.plan.conflictPolicy).isEqualTo(ConflictPolicy.ONE_PER_SYMBOL)
        assertThat(d.plan.maximumOpenRiskPercent!!.toInt()).isEqualTo(3)
        assertThat(d.plan.longWhen).isNotNull()
        assertThat(d.indicators.map { it.id }).contains("EMA_50", "WVWAP")
        // Each setup keeps only the indicators it (and the plan context) uses.
        assertThat(
            d.setups
                .first { it.id == "SFP_HIGH" }
                .def.indicators
                .map { it.id },
        ).doesNotContain("WVWAP", "RVOL")
        assertThat(d.setups.first { it.id == "BREAKOUT" }.appliesWhen).isNotNull()
        assertThat(
            d.setups
                .first { it.id == "SFP_LOW" }
                .def.exit.partialTakeProfit,
        ).isNotNull()
        assertThat(d.minimumHistoryBars).isGreaterThanOrEqualTo(50 * 3)
    }

    @Test
    fun `mistakes inside a setup or the plan context point at that setup or context`() {
        val bad =
            Plans
                .rangePlan()
                .replace("\"comparison\": \"LT\", \"right\": \"RANGE_HIGH.value\"", "\"comparison\": \"LT\", \"right\": \"NOPE.value\"")
                .replace("{\"left\": \"CLOSE\", \"comparison\": \"GT\", \"right\": \"EMA_50\"}", "{\"left\": \"CLOSE\", \"comparison\": \"GT\", \"right\": \"EMA_50.upper\"}")
        val r = validate(bad)
        assertThat(r.outcome).isEqualTo(ValidationOutcome.VALIDATION_FAILED)
        assertThat(r.errors.map { it.path }).anyMatch { it.startsWith("$.setups[1].entryRules") }
        assertThat(r.errors.first { it.path.startsWith("$.setups[1]") }.message).startsWith("Setup SFP_HIGH:").contains("NOPE")
        assertThat(r.errors.map { it.path }).anyMatch { it.startsWith("$.context.longWhen") }
    }

    @Test
    fun `plan-level rules are checked`() {
        val dup = validate(Plans.rangePlan().replace("\"id\": \"SFP_HIGH\"", "\"id\": \"SFP_LOW\""))
        assertThat(dup.errors.map { it.code }).contains("DUPLICATE_SETUP_ID")
        val noAlloc = validate(Plans.rangePlan(capital = "ALLOCATED"))
        assertThat(noAlloc.errors.filter { it.code == "MISSING_PARAMETER" }.map { it.path }).contains("$.setups[0].allocationPercent")
        assertThat(validate(Plans.rangePlan(capital = "ALLOCATED", allocations = true)).outcome).isEqualTo(ValidationOutcome.VALIDATED)
        val over = validate(Plans.rangePlan(capital = "ALLOCATED", allocations = true).replace("\"allocationPercent\": 40", "\"allocationPercent\": 60"))
        assertThat(over.errors.map { it.message }).anyMatch { it.contains("add up to 120%") }
        val shortWithoutPermission = validate(Plans.rangePlan().replace("\"allowShort\": true", "\"allowShort\": false"))
        assertThat(shortWithoutPermission.errors.map { it.path }).contains("$.setups[1].direction")
        val tooLong = validate(Plans.rangePlan(timeframe = "1h").replace("{\"id\": \"EMA_50\", \"type\": \"EMA\", \"period\": 50}", "{\"id\": \"EMA_50\", \"type\": \"EMA\", \"period\": 200, \"timeframe\": \"1d\"}"))
        assertThat(tooLong.errors.map { it.code }).`as`("a setup's history limit is not lost inside a plan").contains("HISTORY_TOO_LONG")
        val unknown = validate(Plans.rangePlan().replace("\"priority\": 3,", "\"priority\": 3, \"leverage\": 5,"))
        assertThat(unknown.outcome).isNotEqualTo(ValidationOutcome.VALIDATED)
    }

    @Test
    fun `a plan is stored, explained and activated like any strategy`() {
        val r = e.strategies.create(tree(Plans.rangePlan()))
        assertThat(r.strategy.status).`as`(r.validation.issues.toString()).isEqualTo(StrategyStatus.VALIDATED)
        assertThat(r.strategy.name).isEqualTo("Range playbook")
        val def = e.strategies.definition(r.version!!.id)
        assertThat(def.setups).hasSize(3)
        assertThat(r.explanation)
            .contains("Trading plan with 3 setup(s)")
            .contains("Longs are only taken while")
            .contains("One position per symbol")
            .contains("No new position while more than 3% of equity is at risk")
            .contains("Setup 1: Swing failure at range low (priority 1)")
            .contains("Setup 3: Breakout continuation (priority 3). Applies only while the close price is above the weekly VWAP")
            .contains("Take 50% of the position off at a 3% gain")
            .contains("Across the whole plan: At most 3 open positions")
    }
}
