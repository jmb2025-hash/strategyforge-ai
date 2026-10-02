package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.Instrument
import app.strategyforge.engine.market.MarketDataProvider
import app.strategyforge.engine.market.ProviderResult
import app.strategyforge.engine.market.Timeframe
import app.strategyforge.engine.safety.RealMoneyPolicy
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class IssueCategory { SECURITY, FORMAT, SCHEMA, SEMANTIC, UNKNOWN_CONTENT, DATA, RISK }

enum class IssueSeverity { ERROR, WARNING, REVIEW }

@Serializable
data class ValidationIssue(
    val code: String,
    val category: IssueCategory,
    val severity: IssueSeverity,
    val path: String,
    val message: String,
)

enum class ValidationOutcome { VALIDATED, VALIDATION_FAILED, MANUAL_REVIEW_REQUIRED }

data class ValidationReport(
    val outcome: ValidationOutcome,
    val issues: List<ValidationIssue>,
    val unknownFields: List<String>,
    /** Parsed document (null when the input could not be parsed safely). */
    val document: ObjectNode?,
    val definition: StrategyDefinition?,
) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == IssueSeverity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == IssueSeverity.WARNING }
}

/** Instrument master lookup used for universe checks. */
fun interface InstrumentLookup {
    fun findBySymbol(symbol: String): Instrument?
}

/** The market-data source strategies will run on, used for data-availability checks. */
interface ActiveMarketData {
    val name: String
    val provider: MarketDataProvider
}

/**
 * Strategy import and validation pipeline (FR-041..FR-043, section 9, D-005):
 * size and encoding -> strict parse -> prohibited content -> unknown fields -> JSON Schema
 * -> semantic rules -> data availability. Executable or real-money content is rejected;
 * unrecognised but potentially meaningful fields make the version Manual Review Required.
 * AI-compiled documents go through exactly the same pipeline.
 */
class StrategyValidator(
    private val instruments: InstrumentLookup,
    private val market: () -> ActiveMarketData,
) {
    private val mapper: ObjectMapper =
        ObjectMapper(
            JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints
                        .builder()
                        .maxNestingDepth(MAX_DEPTH)
                        .maxStringLength(20_000)
                        .maxNumberLength(40)
                        .build(),
                ).build(),
        ).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .also { it.factory.disable(JsonParser.Feature.ALLOW_COMMENTS) }

    private val schema =
        JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(javaClass.getResourceAsStream("/strategy/strategy-schema-1.0.json"))

    private val planSchema =
        JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(javaClass.getResourceAsStream("/strategy/trading-plan-schema-2.0.json"))

    fun validateBytes(bytes: ByteArray): ValidationReport {
        if (bytes.size > MAX_BYTES) return fail(ValidationIssue("TOO_LARGE", IssueCategory.FORMAT, IssueSeverity.ERROR, "$", "Strategy file exceeds ${MAX_BYTES / 1024} KiB"))
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return fail(ValidationIssue("BOM_NOT_ALLOWED", IssueCategory.FORMAT, IssueSeverity.ERROR, "$", "UTF-8 byte-order mark is not allowed"))
        }
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return fail(ValidationIssue("INVALID_ENCODING", IssueCategory.FORMAT, IssueSeverity.ERROR, "$", "File is not valid UTF-8"))
            }
        if (text.any { it.code < 0x20 && it !in "\t\r\n" }) return fail(ValidationIssue("CONTROL_CHARACTERS", IssueCategory.SECURITY, IssueSeverity.ERROR, "$", "Control characters are not allowed"))
        val node =
            try {
                mapper.readTree(text)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                return fail(ValidationIssue("INVALID_JSON", IssueCategory.FORMAT, IssueSeverity.ERROR, "$", "Not valid strict JSON: ${e.originalMessage.take(200)}"))
            }
        if (node !is ObjectNode) return fail(ValidationIssue("NOT_AN_OBJECT", IssueCategory.FORMAT, IssueSeverity.ERROR, "$", "Top level must be a JSON object"))
        return validateDocument(node)
    }

    fun validateDocument(input: ObjectNode): ValidationReport {
        val doc = input.deepCopy()
        val issues = mutableListOf<ValidationIssue>()
        scan(doc, "$", issues)
        if (issues.any { it.category == IssueCategory.SECURITY }) return ValidationReport(ValidationOutcome.VALIDATION_FAILED, issues, emptyList(), doc, null)

        val unknown = mutableListOf<String>()
        val pruned = doc.deepCopy()
        val plan = TradingPlans.isPlan(doc)
        prune(pruned, if (plan) Shape.PLAN else Shape.ROOT, "$", unknown)
        unknown.forEach { issues += ValidationIssue("UNKNOWN_FIELD", IssueCategory.UNKNOWN_CONTENT, IssueSeverity.REVIEW, it, "Unrecognised field requires manual review; it is never ignored") }

        (if (plan) planSchema else schema).validate(pruned).forEach { m ->
            issues += ValidationIssue("SCHEMA_${m.type.uppercase()}", IssueCategory.SCHEMA, IssueSeverity.ERROR, m.instanceLocation.toString(), m.message.take(300))
        }
        if (issues.none { it.category == IssueCategory.SCHEMA }) {
            if (plan) planSemantic(pruned, issues) else semantic(pruned, issues)
        }
        val hasErrors = issues.any { it.severity == IssueSeverity.ERROR }
        val definition = if (!hasErrors) StrategyDefinition.from(pruned) else null
        if (definition != null && plan) {
            val declared = pruned["dataRequirements"]["minimumHistoryBars"].intValue()
            if (declared < definition.minimumHistoryBars) {
                issues += ValidationIssue("HISTORY_RAISED", IssueCategory.DATA, IssueSeverity.WARNING, "$.dataRequirements.minimumHistoryBars", "minimumHistoryBars raised from $declared to ${definition.minimumHistoryBars} for the plan's indicators")
            }
        }
        if (definition != null) dataAvailability(definition, issues)
        val outcome =
            when {
                issues.any { it.severity == IssueSeverity.ERROR } -> ValidationOutcome.VALIDATION_FAILED
                unknown.isNotEmpty() -> ValidationOutcome.MANUAL_REVIEW_REQUIRED
                else -> ValidationOutcome.VALIDATED
            }
        return ValidationReport(outcome, issues, unknown, doc, if (outcome == ValidationOutcome.VALIDATION_FAILED) null else definition)
    }

    private fun fail(issue: ValidationIssue) = ValidationReport(ValidationOutcome.VALIDATION_FAILED, listOf(issue), emptyList(), null, null)

    // ---------------------------------------------------------------- security scan

    private fun scan(
        n: JsonNode,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        when {
            n.isObject ->
                n.fields().forEach { (k, v) ->
                    val p = "$path.$k"
                    if (RealMoneyPolicy.isProhibitedField(k)) {
                        issues += ValidationIssue("REAL_MONEY_FIELD", IssueCategory.SECURITY, IssueSeverity.ERROR, p, "Field '$k' implies real-money trading, which does not exist")
                    } else if (isExecutableKey(k)) {
                        issues += ValidationIssue("EXECUTABLE_FIELD", IssueCategory.SECURITY, IssueSeverity.ERROR, p, "Field '$k' implies executable, network, file or database content")
                    }
                    checkText(k, p, issues)
                    scan(v, p, issues)
                }
            n.isArray -> n.forEachIndexed { i, v -> scan(v, "$path[$i]", issues) }
            n.isTextual -> checkText(n.asText(), path, issues)
        }
    }

    private fun checkText(
        s: String,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        PROHIBITED_VALUES.firstOrNull { (_, re) -> re.containsMatchIn(s) }?.let { (label, _) ->
            issues += ValidationIssue("PROHIBITED_CONTENT", IssueCategory.SECURITY, IssueSeverity.ERROR, path, "Prohibited content detected ($label)")
        }
        INJECTION.firstOrNull { it.containsMatchIn(s) }?.let {
            issues += ValidationIssue("PROMPT_INJECTION", IssueCategory.SECURITY, IssueSeverity.ERROR, path, "Instruction-like text attempting to change rules, limits or permissions is not accepted")
        }
    }

    // ---------------------------------------------------------------- unknown fields

    private enum class Shape { ROOT, PLAN, CONTEXT, PLAN_RULES, SETUP, METADATA, UNIVERSE, DATA, INDICATOR, GROUP, CONDITION, EXIT, PARTIAL, SIZING, ORDER, RISK, SCHEDULE, LEAF }

    private val known: Map<Shape, Map<String, Shape>> =
        mapOf(
            Shape.ROOT to
                mapOf(
                    "schemaVersion" to Shape.LEAF,
                    "strategyId" to Shape.LEAF,
                    "version" to Shape.LEAF,
                    "metadata" to Shape.METADATA,
                    "universe" to Shape.UNIVERSE,
                    "dataRequirements" to Shape.DATA,
                    "entryRules" to Shape.GROUP,
                    "shortEntryRules" to Shape.GROUP,
                    "exitRules" to Shape.EXIT,
                    "positionSizing" to Shape.SIZING,
                    "orderInstructions" to Shape.ORDER,
                    "riskLimits" to Shape.RISK,
                    "schedule" to Shape.SCHEDULE,
                    "inactivityConditions" to Shape.LEAF,
                ),
            Shape.PLAN to
                mapOf(
                    "schemaVersion" to Shape.LEAF,
                    "strategyId" to Shape.LEAF,
                    "version" to Shape.LEAF,
                    "metadata" to Shape.METADATA,
                    "universe" to Shape.UNIVERSE,
                    "dataRequirements" to Shape.DATA,
                    "context" to Shape.CONTEXT,
                    "planRules" to Shape.PLAN_RULES,
                    "setups" to Shape.SETUP,
                    "orderInstructions" to Shape.ORDER,
                    "riskLimits" to Shape.RISK,
                    "schedule" to Shape.SCHEDULE,
                    "inactivityConditions" to Shape.LEAF,
                ),
            Shape.CONTEXT to mapOf("longWhen" to Shape.GROUP, "shortWhen" to Shape.GROUP),
            Shape.PLAN_RULES to listOf("conflictPolicy", "capitalPolicy", "maximumOpenRiskPercent").associateWith { Shape.LEAF },
            Shape.SETUP to
                listOf("id", "name", "description", "priority", "allocationPercent", "direction", "maximumOpenPositions").associateWith { Shape.LEAF } +
                mapOf(
                    "appliesWhen" to Shape.GROUP,
                    "entryRules" to Shape.GROUP,
                    "shortEntryRules" to Shape.GROUP,
                    "exitRules" to Shape.EXIT,
                    "positionSizing" to Shape.SIZING,
                ),
            Shape.METADATA to listOf("name", "description", "assetClass", "timeframe", "createdBy", "direction", "tags").associateWith { Shape.LEAF },
            Shape.UNIVERSE to mapOf("symbols" to Shape.LEAF),
            Shape.DATA to mapOf("minimumHistoryBars" to Shape.LEAF, "maximumQuoteAgeSeconds" to Shape.LEAF, "indicators" to Shape.INDICATOR),
            Shape.INDICATOR to listOf("id", "type", "period", "fastPeriod", "slowPeriod", "signalPeriod", "standardDeviations", "source", "timeframe", "anchor", "anchorPoint").associateWith { Shape.LEAF },
            Shape.GROUP to mapOf("operator" to Shape.LEAF, "conditions" to Shape.CONDITION),
            Shape.CONDITION to listOf("left", "comparison", "right", "offsetBars").associateWith { Shape.LEAF },
            Shape.EXIT to
                mapOf(
                    "stopLossPercent" to Shape.LEAF,
                    "takeProfitPercent" to Shape.LEAF,
                    "trailingStopPercent" to Shape.LEAF,
                    "maximumHoldingBars" to Shape.LEAF,
                    "conditions" to Shape.GROUP,
                    "shortConditions" to Shape.GROUP,
                    "partialTakeProfit" to Shape.PARTIAL,
                ),
            Shape.PARTIAL to listOf("atPercent", "closePercent", "moveStopToEntry").associateWith { Shape.LEAF },
            Shape.SIZING to mapOf("method" to Shape.LEAF, "value" to Shape.LEAF),
            Shape.ORDER to mapOf("orderType" to Shape.LEAF, "timeInForce" to Shape.LEAF, "limitOffsetPercent" to Shape.LEAF),
            Shape.RISK to
                listOf(
                    "maximumOpenPositions",
                    "maximumDailyTrades",
                    "maximumDailyLossPercent",
                    "maximumDrawdownPercent",
                    "maximumPositionPercent",
                    "maximumConsecutiveLosses",
                    "allowShort",
                    "maximumDailyLosingTrades",
                ).associateWith { Shape.LEAF },
            Shape.SCHEDULE to mapOf("evaluate" to Shape.LEAF, "sessions" to Shape.LEAF),
        )

    /** Removes unknown fields from [n] (recording their paths) so schema checks see only known structure. */
    private fun prune(
        n: JsonNode,
        shape: Shape,
        path: String,
        unknown: MutableList<String>,
    ) {
        if (shape == Shape.LEAF) return
        if (n is ArrayNode) {
            n.forEachIndexed { i, e -> prune(e, if (shape == Shape.CONDITION && e.has("operator")) Shape.GROUP else shape, "$path[$i]", unknown) }
            return
        }
        if (n !is ObjectNode) return
        val allowed = known[shape] ?: return
        n.fieldNames().asSequence().toList().forEach { k ->
            val child = allowed[k]
            if (child == null) {
                unknown += "$path.$k"
                n.remove(k)
            } else {
                prune(n.get(k), child, "$path.$k", unknown)
            }
        }
    }

    // ---------------------------------------------------------------- semantic rules

    private fun semantic(
        doc: JsonNode,
        issues: MutableList<ValidationIssue>,
    ) {
        val specs = indicatorRules(doc, issues)
        val maxOffset = ruleChecks(doc, specs, issues)
        settingsRules(doc, specs, maxOffset, issues)
        universeRules(doc, issues)
    }

    private fun addError(
        issues: MutableList<ValidationIssue>,
        code: String,
        path: String,
        msg: String,
    ) {
        issues += ValidationIssue(code, IssueCategory.SEMANTIC, IssueSeverity.ERROR, path, msg)
    }

    /**
     * Semantic rules for a trading plan (D-045): plan indicators once, then each setup as a complete
     * strategy and each context or applies-when condition group as entry rules, with issue paths
     * pointing back into the plan.
     */
    private fun planSemantic(
        doc: JsonNode,
        issues: MutableList<ValidationIssue>,
    ) {
        val out = linkedSetOf<ValidationIssue>()
        indicatorRules(doc, issues)
        val setups = doc["setups"]
        val ids = setups.map { it["id"].asText() }
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { id ->
            out += ValidationIssue("DUPLICATE_SETUP_ID", IssueCategory.SEMANTIC, IssueSeverity.ERROR, "$.setups", "Setup id '$id' is used more than once")
        }
        val capital = doc["planRules"]?.get("capitalPolicy")?.asText() ?: "SHARED"
        val allocations = setups.mapNotNull { it["allocationPercent"]?.decimalValue() }
        if (capital == "ALLOCATED") {
            setups.forEachIndexed { i, s -> if (s["allocationPercent"] == null) out += ValidationIssue("MISSING_PARAMETER", IssueCategory.SEMANTIC, IssueSeverity.ERROR, "$.setups[$i].allocationPercent", "capitalPolicy ALLOCATED requires every setup's allocationPercent") }
            val total = allocations.fold(BigDecimal.ZERO, BigDecimal::add)
            if (total > BigDecimal(100)) out += ValidationIssue("UNSAFE_VALUE", IssueCategory.SEMANTIC, IssueSeverity.ERROR, "$.setups", "Setup allocations add up to ${total.stripTrailingZeros().toPlainString()}%, more than 100%")
        } else if (allocations.isNotEmpty()) {
            out += ValidationIssue("IGNORED_SETTING", IssueCategory.RISK, IssueSeverity.WARNING, "$.planRules.capitalPolicy", "allocationPercent only applies when capitalPolicy is ALLOCATED")
        }

        fun mapped(
            sub: List<ValidationIssue>,
            prefix: String,
            label: String,
            moves: Map<String, String>,
        ) {
            sub.forEach { v ->
                if (v.path.startsWith("$.dataRequirements.indicators") || v.code == "HISTORY_RAISED") return@forEach
                val move = moves.entries.firstOrNull { v.path == it.key || v.path.startsWith(it.key + ".") || v.path.startsWith(it.key + "[") }
                out +=
                    if (move != null) {
                        v.copy(path = prefix + move.value + v.path.removePrefix(move.key), message = "$label: ${v.message}")
                    } else {
                        v
                    }
            }
        }
        setups.forEachIndexed { i, s ->
            val sub = mutableListOf<ValidationIssue>()
            semantic(TradingPlans.setupDocument(doc, s), sub)
            val label = "Setup ${s["id"].asText()}"
            mapped(
                sub,
                "$.setups[$i]",
                label,
                mapOf("$.entryRules" to ".entryRules", "$.shortEntryRules" to ".shortEntryRules", "$.exitRules" to ".exitRules", "$.positionSizing" to ".positionSizing", "$.metadata.direction" to ".direction"),
            )
            s["appliesWhen"]?.let { g -> probe(doc, g, "$.setups[$i].appliesWhen", "$label applies-when", ::mapped) }
        }
        doc["context"]?.get("longWhen")?.let { probe(doc, it, "$.context.longWhen", "Plan context (longs)", ::mapped) }
        doc["context"]?.get("shortWhen")?.let { probe(doc, it, "$.context.shortWhen", "Plan context (shorts)", ::mapped) }
        issues += out
    }

    private fun probe(
        doc: JsonNode,
        group: JsonNode,
        path: String,
        label: String,
        mapped: (List<ValidationIssue>, String, String, Map<String, String>) -> Unit,
    ) {
        val sub = mutableListOf<ValidationIssue>()
        semantic(TradingPlans.probeDocument(doc, group), sub)
        mapped(sub.filter { it.path.startsWith("$.entryRules") }, path, label, mapOf("$.entryRules" to ""))
    }

    private fun indicatorRules(
        doc: JsonNode,
        issues: MutableList<ValidationIssue>,
    ): Map<String, IndicatorSpec> {
        fun err(
            code: String,
            path: String,
            msg: String,
        ) = addError(issues, code, path, msg)
        val indicators = doc["dataRequirements"]["indicators"]
        val specs = mutableMapOf<String, IndicatorSpec>()
        indicators.forEachIndexed { i, ind ->
            val p = "$.dataRequirements.indicators[$i]"
            val id = ind["id"].asText()
            if (id in specs || PriceField.entries.any { it.name == id }) err("DUPLICATE_INDICATOR_ID", p, "Indicator id '$id' is duplicated or reserved")
            val type = IndicatorType.valueOf(ind["type"].asText())
            val present = ind.fieldNames().asSequence().toSet() - setOf("id", "type", "source")
            val required =
                when (type) {
                    IndicatorType.MACD -> setOf("fastPeriod", "slowPeriod", "signalPeriod")
                    IndicatorType.BOLLINGER_BANDS -> setOf("period", "standardDeviations")
                    // Candlestick patterns are fixed shapes (D-036).
                    in IndicatorType.PATTERNS -> emptySet()
                    IndicatorType.PERIOD_LEVELS, IndicatorType.VWAP -> setOf("anchor")
                    IndicatorType.FUNDING_RATE -> emptySet()
                    // A volume profile covers either the previous N bars or the previous calendar period (D-042).
                    IndicatorType.VOLUME_PROFILE -> if (ind.has("anchor")) setOf("anchor") else setOf("period")
                    else -> setOf("period")
                }
            val optional =
                buildSet {
                    if (type in IndicatorType.HIGHER_TIMEFRAME_CAPABLE) add("timeframe")
                    if (type == IndicatorType.ANCHORED_VWAP) add("anchorPoint")
                }
            (required - present).forEach { err("MISSING_PARAMETER", p, "$type requires '$it'") }
            (present - required - optional).forEach { err("UNSUPPORTED_PARAMETER", "$p.$it", "$type does not accept '$it'") }
            if (type in IndicatorType.DERIVATIVES && doc["metadata"]["assetClass"]?.asText() != "CRYPTO") {
                err("DERIVATIVES_CRYPTO_ONLY", "$p.type", "$type uses perpetual-futures data, which exists for crypto strategies only")
            }
            if (type == IndicatorType.VOLUME_PROFILE && ind.has("anchor") && ind.has("period")) err("CONTRADICTORY_PARAMETERS", p, "VOLUME_PROFILE takes either 'period' or 'anchor', not both")
            ind["timeframe"]?.asText()?.let { tf ->
                val base = Timeframe.of(doc["metadata"]["timeframe"].asText())
                val anchor = Anchor.ofCode(tf)
                if (anchor == null) {
                    err("UNSUPPORTED_PARAMETER", "$p.timeframe", "timeframe must be 1d, 1w or 1M")
                } else if (anchor == Anchor.DAY && base == Timeframe.D1) {
                    err("CONTRADICTORY_PARAMETERS", "$p.timeframe", "The strategy already uses daily bars; omit timeframe")
                }
            }
            if (type == IndicatorType.MACD && ind["fastPeriod"] != null && ind["slowPeriod"] != null && ind["fastPeriod"].intValue() >= ind["slowPeriod"].intValue()) {
                err("CONTRADICTORY_PARAMETERS", p, "MACD fastPeriod must be less than slowPeriod")
            }
            if ((required - present).isEmpty()) {
                specs[id] =
                    IndicatorSpec(
                        id,
                        type,
                        ind["period"]?.intValue(),
                        ind["fastPeriod"]?.intValue(),
                        ind["slowPeriod"]?.intValue(),
                        ind["signalPeriod"]?.intValue(),
                        ind["standardDeviations"]?.decimalValue(),
                        ind["source"]?.asText()?.let { PriceField.valueOf(it) } ?: PriceField.CLOSE,
                        ind["timeframe"]?.asText()?.let { Anchor.ofCode(it) },
                        ind["anchor"]?.asText()?.let { Anchor.valueOf(it) },
                        ind["anchorPoint"]?.asText()?.let { AnchorPoint.valueOf(it) },
                    )
            }
        }
        return specs
    }

    private fun ruleChecks(
        doc: JsonNode,
        specs: Map<String, IndicatorSpec>,
        issues: MutableList<ValidationIssue>,
    ): Int {
        fun err(
            code: String,
            path: String,
            msg: String,
        ) = addError(issues, code, path, msg)
        var maxOffset = 0
        var conditionCount = 0

        fun checkGroup(
            g: JsonNode,
            path: String,
            depth: Int,
        ) {
            if (depth > MAX_RULE_DEPTH) {
                err("RULES_TOO_DEEP", path, "Rule groups may nest at most $MAX_RULE_DEPTH levels")
                return
            }
            val pairs = mutableListOf<Triple<String, String, Comparison>>()
            g["conditions"].forEachIndexed { i, c ->
                val cp = "$path.conditions[$i]"
                if (c.has("operator")) {
                    checkGroup(c, cp, depth + 1)
                    return@forEachIndexed
                }
                conditionCount++
                val cmp = Comparison.valueOf(c["comparison"].asText())
                val offset = c["offsetBars"]?.intValue() ?: 0
                if (offset < 0) err("FUTURE_DATA_REFERENCE", "$cp.offsetBars", "Negative offsets reference future bars and are prohibited")
                maxOffset = maxOf(maxOffset, offset)
                val sides = listOf(c["left"], c["right"])
                sides.forEachIndexed { si, o ->
                    if (o.isTextual) {
                        val ref = o.asText()
                        val base = ref.substringBefore('.')
                        val comp = if (ref.contains('.')) ref.substringAfter('.') else "value"
                        val price = PriceField.entries.any { it.name == ref }
                        val spec = specs[base]
                        if (!price && spec == null) err("UNKNOWN_REFERENCE", "$cp.${if (si == 0) "left" else "right"}", "'$ref' is not a declared indicator or price field")
                        if (spec != null && comp !in spec.components()) err("UNKNOWN_COMPONENT", "$cp.${if (si == 0) "left" else "right"}", "${spec.type} has no component '$comp'")
                    }
                }
                if (sides.all { it.isNumber }) err("CONSTANT_COMPARISON", cp, "Comparing two constants is meaningless")
                if ((cmp == Comparison.CROSSES_ABOVE || cmp == Comparison.CROSSES_BELOW) && sides.first().isNumber) err("INVALID_CROSS", cp, "The left side of a cross must be a series")
                pairs += Triple(c["left"].toString(), c["right"].toString(), cmp)
            }
            if (g["operator"].asText() == "ALL") {
                pairs.forEach { (l, r, cmp) ->
                    val contradiction =
                        pairs.any { (l2, r2, c2) ->
                            l == l2 && r == r2 && ((cmp in UPPER && c2 in LOWER) || (cmp == Comparison.CROSSES_ABOVE && c2 == Comparison.CROSSES_BELOW))
                        }
                    if (contradiction) err("CONTRADICTORY_RULES", path, "Conditions on $l vs $r can never be true together")
                }
                // x > a AND x < b with a >= b is unsatisfiable.
                val lowers = pairs.filter { it.third in UPPER && it.second.toBigDecimalOrNull() != null }.groupBy({ it.first }, { it.second.toBigDecimal() })
                val uppers = pairs.filter { it.third in LOWER && it.second.toBigDecimalOrNull() != null }.groupBy({ it.first }, { it.second.toBigDecimal() })
                lowers.forEach { (lhs, lo) -> uppers[lhs]?.let { hi -> if (lo.max() >= hi.min()) err("CONTRADICTORY_RULES", path, "$lhs must exceed ${lo.max()} and be below ${hi.min()}") } }
            }
        }
        checkGroup(doc["entryRules"], "$.entryRules", 1)
        doc["shortEntryRules"]?.let { checkGroup(it, "$.shortEntryRules", 1) }
        doc["exitRules"]["conditions"]?.let { checkGroup(it, "$.exitRules.conditions", 1) }
        doc["exitRules"]["shortConditions"]?.let { checkGroup(it, "$.exitRules.shortConditions", 1) }
        if (conditionCount > MAX_CONDITIONS) err("TOO_MANY_CONDITIONS", "$", "At most $MAX_CONDITIONS conditions are allowed")
        return maxOffset
    }

    private fun settingsRules(
        doc: JsonNode,
        specs: Map<String, IndicatorSpec>,
        maxOffset: Int,
        issues: MutableList<ValidationIssue>,
    ) {
        fun err(
            code: String,
            path: String,
            msg: String,
        ) = addError(issues, code, path, msg)

        val base = Timeframe.of(doc["metadata"]["timeframe"].asText())
        val assetClass = doc["metadata"]["assetClass"].asText()
        val lookback =
            StrategyDefinition.requiredHistory(
                specs.values.toList(),
                maxOffset,
                base,
                app.strategyforge.engine.market.AssetClass
                    .valueOf(assetClass),
            )
        val minBars = doc["dataRequirements"]["minimumHistoryBars"].intValue()
        if (lookback > MAX_HISTORY_BARS) {
            err(
                "HISTORY_TOO_LONG",
                "$.dataRequirements.indicators",
                "The indicators need $lookback ${base.code} bars of history; at most $MAX_HISTORY_BARS can be loaded. Use a longer strategy timeframe or shorter periods.",
            )
        } else if (minBars < lookback) {
            // Raised automatically (D-042): the strategy loads what its indicators need.
            issues += ValidationIssue("HISTORY_RAISED", IssueCategory.DATA, IssueSeverity.WARNING, "$.dataRequirements.minimumHistoryBars", "minimumHistoryBars raised from $minBars to $lookback for the declared indicators")
        }

        val sizing = doc["positionSizing"]
        val value = sizing["value"].decimalValue()
        when (sizing["method"].asText()) {
            "PERCENT_OF_EQUITY" -> if (value > BigDecimal(100)) err("UNSAFE_VALUE", "$.positionSizing.value", "Percent of equity cannot exceed 100")
            "RISK_PERCENT" ->
                if (value > BigDecimal(10)) {
                    err("UNSAFE_VALUE", "$.positionSizing.value", "Risking more than 10% of equity per trade is not allowed")
                } else if (value > BigDecimal(2)) {
                    issues += ValidationIssue("HIGH_RISK_PER_TRADE", IssueCategory.RISK, IssueSeverity.WARNING, "$.positionSizing.value", "Risking more than 2% of equity per trade is aggressive")
                }
            "FIXED_QUANTITY" -> if (value > BigDecimal(1_000_000)) err("UNSAFE_VALUE", "$.positionSizing.value", "Fixed quantity too large")
        }
        val order = doc["orderInstructions"]
        if (order["orderType"].asText() == "LIMIT" && order["limitOffsetPercent"] == null) err("MISSING_PARAMETER", "$.orderInstructions", "LIMIT orders require limitOffsetPercent")
        if (order["orderType"].asText() == "MARKET" && order["limitOffsetPercent"] != null) err("UNSUPPORTED_PARAMETER", "$.orderInstructions.limitOffsetPercent", "MARKET orders take no limit offset")
        val allowShort = doc["riskLimits"]["allowShort"].booleanValue()
        val direction = doc["metadata"]["direction"]?.asText() ?: "LONG_ONLY"
        if (direction != "LONG_ONLY" && !allowShort) err("CONTRADICTORY_SETTINGS", "$.metadata.direction", "$direction requires riskLimits.allowShort = true")
        // Two-direction strategies (D-042): separate short entry rules, and optionally short exit conditions.
        if (direction == "BOTH" && doc["shortEntryRules"] == null) err("MISSING_PARAMETER", "$.shortEntryRules", "Direction BOTH requires shortEntryRules")
        if (direction != "BOTH" && doc["shortEntryRules"] != null) err("UNSUPPORTED_PARAMETER", "$.shortEntryRules", "shortEntryRules is only used when metadata.direction is BOTH")
        if (direction != "BOTH" && doc["exitRules"]["shortConditions"] != null) {
            err("UNSUPPORTED_PARAMETER", "$.exitRules.shortConditions", "exitRules.shortConditions is only used when metadata.direction is BOTH")
        }
        val stop = doc["exitRules"]["stopLossPercent"].decimalValue()
        doc["exitRules"]["partialTakeProfit"]?.let { pt ->
            if (pt["atPercent"].decimalValue() >= doc["exitRules"]["takeProfitPercent"].decimalValue()) {
                err("CONTRADICTORY_SETTINGS", "$.exitRules.partialTakeProfit.atPercent", "The partial target must be closer than takeProfitPercent")
            }
        }
        val trailing = doc["exitRules"]["trailingStopPercent"]?.decimalValue()
        if (trailing != null && trailing > stop.multiply(BigDecimal(5))) {
            issues += ValidationIssue("WIDE_TRAILING_STOP", IssueCategory.RISK, IssueSeverity.WARNING, "$.exitRules.trailingStopPercent", "Trailing stop is much wider than the stop loss")
        }
    }

    private fun universeRules(
        doc: JsonNode,
        issues: MutableList<ValidationIssue>,
    ) {
        fun err(
            code: String,
            path: String,
            msg: String,
        ) = addError(issues, code, path, msg)
        val assetClass = doc["metadata"]["assetClass"].asText()
        val symbols = doc["universe"]["symbols"].map { it.asText() }
        symbols.forEachIndexed { i, s ->
            val inst = instruments.findBySymbol(s)
            when {
                inst == null -> err("UNKNOWN_SYMBOL", "$.universe.symbols[$i]", "$s is not in the instrument master")
                !inst.active -> err("INACTIVE_SYMBOL", "$.universe.symbols[$i]", "$s is deactivated")
                inst.assetClass.name != assetClass -> err("ASSET_CLASS_MISMATCH", "$.universe.symbols[$i]", "$s is ${inst.assetClass}, strategy is $assetClass")
            }
        }
    }

    // ---------------------------------------------------------------- data availability

    private fun dataAvailability(
        def: StrategyDefinition,
        issues: MutableList<ValidationIssue>,
    ) {
        val active = market()
        val native = active.provider.nativeTimeframes(def.assetClass)
        val obtainable =
            def.timeframe in native || (def.timeframe in setOf(Timeframe.M5, Timeframe.M15) && Timeframe.M1 in native) || (def.timeframe == Timeframe.H4 && Timeframe.H1 in native)
        if (!obtainable) issues += ValidationIssue("TIMEFRAME_UNAVAILABLE", IssueCategory.DATA, IssueSeverity.ERROR, "$.metadata.timeframe", "${def.timeframe.code} bars are not available from ${active.name}")
        def.symbols.forEachIndexed { i, s ->
            val r = active.provider.lookup(s)
            if (r is ProviderResult.Failed || r is ProviderResult.Unsupported) {
                val detail = (r as? ProviderResult.Failed)?.detail ?: (r as ProviderResult.Unsupported).detail
                // Lookup unsupported is not proof of unavailability; only definite "no data" fails.
                if (r is ProviderResult.Failed && r.kind == app.strategyforge.engine.market.FailureKind.NO_DATA) {
                    issues += ValidationIssue("NO_MARKET_DATA", IssueCategory.DATA, IssueSeverity.ERROR, "$.universe.symbols[$i]", "No data for $s from ${active.name}: $detail")
                } else {
                    issues += ValidationIssue("DATA_UNVERIFIED", IssueCategory.DATA, IssueSeverity.WARNING, "$.universe.symbols[$i]", "Availability of $s could not be verified: $detail")
                }
            }
        }
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_DEPTH = 20
        const val MAX_RULE_DEPTH = 3
        const val MAX_CONDITIONS = 50

        /** Most bars a strategy may need loaded per symbol (D-042). */
        const val MAX_HISTORY_BARS = 5000
        const val VALIDATOR_VERSION = "1.0.0"
        private val UPPER = setOf(Comparison.GT, Comparison.GTE)
        private val LOWER = setOf(Comparison.LT, Comparison.LTE)

        private val EXECUTABLE_KEY_WORDS =
            setOf(
                "script",
                "scripts",
                "code",
                "eval",
                "exec",
                "execute",
                "command",
                "cmd",
                "shell",
                "bash",
                "sql",
                "query",
                "url",
                "uri",
                "href",
                "import",
                "require",
                "plugin",
                "webhook",
                "callback",
                "expression",
                "expr",
                "formula",
                "lambda",
                "function",
                "fn",
                "javascript",
                "js",
                "python",
                "py",
                "module",
                "download",
                "filepath",
                "file",
                "path",
                "endpoint",
                "http",
                "https",
                "socket",
                "process",
                "system",
                "reflect",
                "reflection",
            )

        /** Field names are split into words (camelCase, snake_case, kebab-case) and matched exactly. */
        fun isExecutableKey(key: String): Boolean = key.split(Regex("(?<=[a-z0-9])(?=[A-Z])|[_\\-.\\s]+")).map { it.lowercase() }.any { it in EXECUTABLE_KEY_WORDS }

        val PROHIBITED_VALUES: List<Pair<String, Regex>> =
            listOf(
                "script tag" to Regex("(?i)<\\s*script"),
                "javascript URI" to Regex("(?i)javascript:"),
                "evaluation call" to Regex("(?i)\\b(eval|exec|execfile|compile|system|popen|spawn)\\s*\\("),
                "code import" to Regex("(?i)(\\bimport\\s+[a-z_][\\w.]*|\\brequire\\s*\\(|__import__|\\bfrom\\s+[\\w.]+\\s+import\\b)"),
                "function definition" to Regex("(?i)(\\bfunction\\s*\\w*\\s*\\(|=>|\\bdef\\s+\\w+\\s*\\(|\\blambda\\b)"),
                "template or interpolation" to Regex("(\\$\\{|#\\{|\\{\\{|\\$\\()"),
                "prototype pollution" to Regex("(__proto__|\\bconstructor\\s*\\[|\\bprototype\\b)"),
                "SQL statement" to Regex("(?i)\\b(select\\s+.+\\s+from|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from|drop\\s+(table|database)|alter\\s+table|truncate\\s+table)\\b"),
                "shell command" to Regex("(?i)(\\brm\\s+-rf\\b|\\bcurl\\s|\\bwget\\s|\\bbash\\b|\\bsh\\s+-c\\b|\\bpowershell\\b|\\bchmod\\b|`)"),
                "network or file location" to Regex("(?i)(\\b(https?|ftp|file|data|smb|jdbc|ws|wss)://|\\\\\\\\[\\w.]+\\\\|\\bfile:)"),
                "reflection" to Regex("(Class\\.forName|Runtime\\.getRuntime|java\\.lang\\.|System\\.exit|ProcessBuilder|os\\.system|subprocess|getattr\\s*\\()"),
            )

        val INJECTION: List<Regex> =
            listOf(
                Regex("(?i)ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+(instructions|rules|prompts?)"),
                Regex("(?i)disregard\\s+(the\\s+|all\\s+)?(previous|prior|above|system|risk)"),
                Regex("(?i)\\bsystem\\s+prompt\\b"),
                Regex("(?i)\\byou\\s+are\\s+now\\b"),
                Regex("(?i)\\b(override|bypass|disable|remove)\\s+(the\\s+)?(risk|safety|limits?|guardrails?|restrictions?)"),
                Regex("(?i)\\benable\\s+(live|real[- ]?money|real)\\s+trading\\b"),
            )
    }
}
