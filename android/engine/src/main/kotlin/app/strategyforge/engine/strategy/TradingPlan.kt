package app.strategyforge.engine.strategy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/** When two setups want the same symbol (D-045). */
enum class ConflictPolicy {
    /** One position per symbol; the setup with the highest priority (lowest number) wins. */
    ONE_PER_SYMBOL,

    /** Each setup may hold its own position in a symbol, in the same direction only. */
    STACK,
}

/** How setups share the plan's capital (D-045). */
enum class CapitalPolicy {
    /** Every setup sizes from the whole portfolio equity. */
    SHARED,

    /** Each setup sizes from, and is capped at, its allocationPercent of equity. */
    ALLOCATED,
}

/** Plan-wide rules above the setups (D-045). */
data class PlanRules(
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ONE_PER_SYMBOL,
    val capitalPolicy: CapitalPolicy = CapitalPolicy.SHARED,
    /** Most equity at risk (to the stops) across all open positions; null for no cap. */
    val maximumOpenRiskPercent: BigDecimal? = null,
    /** Longs are only taken while this holds (null: always allowed). */
    val longWhen: RuleGroup? = null,
    /** Shorts are only taken while this holds (null: always allowed). */
    val shortWhen: RuleGroup? = null,
) {
    companion object {
        val DEFAULT = PlanRules()
    }
}

/** One setup of a trading plan: its own entries, exits and sizing, applied when its conditions hold. */
data class Setup(
    val id: String,
    val name: String,
    val description: String?,
    val priority: Int,
    val allocationPercent: BigDecimal?,
    val appliesWhen: RuleGroup?,
    val maximumOpenPositions: Int?,
    /** The setup as a complete strategy definition (plan indicators, universe, orders and risk included). */
    val def: StrategyDefinition,
) {
    /**
     * Whether this setup enters on [short] side at bar [i]: the plan's context for that side, the
     * setup's own applies-when conditions and its entry rules must all hold.
     */
    fun wants(
        short: Boolean,
        plan: PlanRules,
        ev: RuleEvaluator,
        i: Int,
    ): Boolean {
        val rules = def.entryFor(short) ?: return false
        appliesWhen?.let { if (!ev.evaluate(it, i)) return false }
        (if (short) plan.shortWhen else plan.longWhen)?.let { if (!ev.evaluate(it, i)) return false }
        return ev.evaluate(rules, i)
    }

    /** The entry side at bar [i]: null when neither or both sides fire (conflicting signals never trade). */
    fun signal(
        plan: PlanRules,
        ev: RuleEvaluator,
        i: Int,
    ): Boolean? {
        val long = wants(false, plan, ev, i)
        val short = wants(true, plan, ev, i)
        return if (long == short) null else short
    }

    companion object {
        const val SINGLE = "MAIN"
    }
}

/**
 * Trading plan documents (schema 2.0, D-045). Each setup is turned into a complete 1.0 strategy
 * document carrying the plan's metadata, universe, indicators it uses, orders, risk limits and
 * inactivity conditions, so every setup goes through exactly the same validation and evaluation
 * as a single strategy.
 */
object TradingPlans {
    const val SCHEMA_VERSION = "2.0"

    fun isPlan(doc: JsonNode): Boolean = doc["schemaVersion"]?.asText() == SCHEMA_VERSION

    /** Indicator ids referenced anywhere under [n] (operands are "ID" or "ID.component"). */
    fun references(n: JsonNode?): Set<String> {
        if (n == null) return emptySet()
        val out = mutableSetOf<String>()

        fun walk(x: JsonNode) {
            when {
                x.isObject -> {
                    listOf("left", "right").forEach { k -> x[k]?.takeIf { it.isTextual }?.let { out += it.asText().substringBefore('.') } }
                    x.fields().forEach { (k, v) -> if (k != "left" && k != "right") walk(v) }
                }
                x.isArray -> x.forEach(::walk)
            }
        }
        walk(n)
        return out
    }

    private fun indicatorsFor(
        plan: JsonNode,
        vararg used: JsonNode?,
    ): JsonNode {
        val ids = used.flatMap { references(it) }.toSet()
        val all = plan["dataRequirements"]?.get("indicators")
        val arr = JsonNodeFactory.instance.arrayNode()
        all?.forEach { i -> if (i["id"]?.asText() in ids) arr.add(i.deepCopy<JsonNode>()) }
        return arr
    }

    private fun base(
        plan: JsonNode,
        name: String,
        direction: String,
        indicators: JsonNode,
    ): ObjectNode {
        val f = JsonNodeFactory.instance
        val doc = f.objectNode()
        doc.put("schemaVersion", "1.0")
        val md = (plan["metadata"]?.deepCopy<JsonNode>() as? ObjectNode) ?: f.objectNode()
        md.put("name", name.take(80))
        md.put("direction", direction)
        md.remove("description")
        doc.set<JsonNode>("metadata", md)
        plan["universe"]?.let { doc.set<JsonNode>("universe", it.deepCopy()) }
        val data = (plan["dataRequirements"]?.deepCopy<JsonNode>() as? ObjectNode) ?: f.objectNode()
        data.set<JsonNode>("indicators", indicators)
        doc.set<JsonNode>("dataRequirements", data)
        plan["orderInstructions"]?.let { doc.set<JsonNode>("orderInstructions", it.deepCopy()) }
        plan["riskLimits"]?.let { doc.set<JsonNode>("riskLimits", it.deepCopy()) }
        plan["inactivityConditions"]?.let { doc.set<JsonNode>("inactivityConditions", it.deepCopy()) }
        return doc
    }

    /** The complete 1.0 strategy document of one setup. */
    fun setupDocument(
        plan: JsonNode,
        setup: JsonNode,
    ): ObjectNode {
        val ctx = plan["context"]
        val doc =
            base(
                plan,
                "${plan["metadata"]?.get("name")?.asText() ?: "Plan"} - ${setup["name"]?.asText() ?: setup["id"]?.asText()}",
                setup["direction"]?.asText() ?: "LONG_ONLY",
                indicatorsFor(plan, setup, ctx),
            )
        listOf("entryRules", "shortEntryRules", "exitRules", "positionSizing").forEach { k -> setup[k]?.let { doc.set<JsonNode>(k, it.deepCopy()) } }
        return doc
    }

    /**
     * A probe document whose only rules are [group], used to validate plan context and applies-when
     * conditions with the same reference, component and contradiction checks as entry rules.
     */
    fun probeDocument(
        plan: JsonNode,
        group: JsonNode,
    ): ObjectNode {
        val f = JsonNodeFactory.instance
        val doc = base(plan, "probe", "LONG_ONLY", indicatorsFor(plan, group))
        doc.set<JsonNode>("entryRules", group.deepCopy())
        doc.set<JsonNode>(
            "exitRules",
            f
                .objectNode()
                .put("stopLossPercent", 1)
                .put("takeProfitPercent", 2)
                .put("maximumHoldingBars", 1),
        )
        doc.set<JsonNode>("positionSizing", f.objectNode().put("method", "FIXED_QUANTITY").put("value", 1))
        (doc["riskLimits"] as? ObjectNode)?.put("allowShort", true)
        return doc
    }

    /** Builds the plan definition from a document that has passed validation. */
    fun definition(plan: JsonNode): StrategyDefinition {
        val rulesNode = plan["planRules"]
        val ctx = plan["context"]
        val rules =
            PlanRules(
                rulesNode?.get("conflictPolicy")?.asText()?.let { ConflictPolicy.valueOf(it) } ?: ConflictPolicy.ONE_PER_SYMBOL,
                rulesNode?.get("capitalPolicy")?.asText()?.let { CapitalPolicy.valueOf(it) } ?: CapitalPolicy.SHARED,
                rulesNode?.get("maximumOpenRiskPercent")?.takeIf { !it.isNull }?.decimalValue(),
                ctx?.get("longWhen")?.let { StrategyDefinition.group(it) },
                ctx?.get("shortWhen")?.let { StrategyDefinition.group(it) },
            )
        val setups =
            plan["setups"]
                .mapIndexed { idx, s ->
                    Setup(
                        s["id"].asText(),
                        s["name"].asText(),
                        s["description"]?.asText(),
                        s["priority"]?.intValue() ?: (idx + 1),
                        s["allocationPercent"]?.takeIf { !it.isNull }?.decimalValue(),
                        s["appliesWhen"]?.let { StrategyDefinition.group(it) },
                        s["maximumOpenPositions"]?.intValue(),
                        StrategyDefinition.from(setupDocument(plan, s)),
                    )
                }.sortedWith(compareBy({ it.priority }, { it.id }))
        // The plan as a whole: every indicator, the longest history, the union of directions, plan-wide risk.
        val indicators = plan["dataRequirements"]["indicators"].map(StrategyDefinition::indicator)
        val directions = setups.map { it.def.direction }.toSet()
        val direction =
            when {
                directions == setOf(Direction.LONG_ONLY) -> Direction.LONG_ONLY
                directions == setOf(Direction.SHORT_ONLY) -> Direction.SHORT_ONLY
                else -> Direction.BOTH
            }
        val first = setups.first().def
        val contextHistory = listOfNotNull(rules.longWhen, rules.shortWhen).maxOfOrNull { StrategyDefinition.requiredHistory(indicators, StrategyDefinition.maxOffsetOf(it), first.timeframe, first.assetClass) } ?: 0
        return first.copy(
            name = plan["metadata"]["name"].asText(),
            description = plan["metadata"]["description"]?.asText(),
            direction = direction,
            indicators = indicators,
            minimumHistoryBars = maxOf(setups.maxOf { it.def.minimumHistoryBars }, contextHistory, plan["dataRequirements"]["minimumHistoryBars"].intValue()),
            setups = setups,
            plan = rules,
        )
    }
}
