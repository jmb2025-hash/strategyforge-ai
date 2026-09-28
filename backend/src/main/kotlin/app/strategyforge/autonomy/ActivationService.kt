package app.strategyforge.autonomy

import app.strategyforge.backtest.BacktestService
import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.security.Crypto
import app.strategyforge.common.web.Problems
import app.strategyforge.identity.CurrentOwner
import app.strategyforge.identity.RecentAuth
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.risk.RiskProfileService
import app.strategyforge.strategy.StrategyService
import app.strategyforge.strategy.StrategyStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class ActivationMode { RECOMMENDATION, AUTONOMOUS }

data class ActivationRequest(
    val mode: ActivationMode = ActivationMode.RECOMMENDATION,
    val portfolioId: UUID,
    val allocationPercent: BigDecimal,
    val disclosureAccepted: Boolean = false,
    val disclosureVersion: String? = null,
)

data class Activation(
    val id: UUID,
    val strategyId: UUID,
    val versionId: UUID,
    val contentHash: String,
    val portfolioId: UUID,
    val mode: ActivationMode,
    val allocationPercent: BigDecimal,
    val backtestId: UUID,
    val disclosureVersion: String?,
    val authorizedAt: Instant?,
    val fingerprint: String?,
    val status: String,
    val createdAt: Instant,
    val endedAt: Instant?,
    val endReason: String?,
)

/** The disclosure the owner must accept before autonomous paper trading (FR-071). */
object AutonomyDisclosure {
    const val VERSION = "2026-09-v1"
    const val TEXT =
        "Autonomous Paper-Trading Mode places SIMULATED orders without asking you first, within the deterministic risk limits you configured. " +
            "No real money is used and no real orders are sent. Results are hypothetical, use simulated fills and may differ from real markets. " +
            "Autonomy stops automatically on stale data, risk-engine failures, repeated errors, loss or drawdown limits and reconciliation failures, " +
            "and must be re-authorized after material changes."
}

/**
 * Strategy activation gates (FR-071, FR-047). Recommendation Mode is the default. Autonomous mode
 * additionally needs the disclosure and recent authentication, and records an authorization
 * fingerprint of everything material; any change to it requires re-authorization (FR-072).
 */
@Service
class ActivationService(
    private val jdbc: JdbcClient,
    private val strategies: StrategyService,
    private val backtests: BacktestService,
    private val portfolios: PortfolioService,
    private val profiles: RiskProfileService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun activate(
        strategyId: UUID,
        req: ActivationRequest,
    ): Activation {
        val s = strategies.lock(strategyId)
        val versionId = s.currentVersionId ?: throw Problems.conflict("no-version", "Strategy has no version")
        val gates = mutableListOf<String>()
        if (s.status !in setOf(StrategyStatus.PAPER_ELIGIBLE, StrategyStatus.PAUSED, StrategyStatus.ACTIVE_RECOMMENDATION)) {
            gates += "Strategy must be Paper Eligible (currently ${s.status})"
        }
        val v = strategies.version(versionId)
        if (v.validationStatus != "VALIDATED") gates += "Current version is not validated"
        val backtest = backtests.eligibleBacktest(versionId)
        if (backtest == null) gates += "A completed backtest without critical integrity errors is required for version hash ${v.contentHash.take(12)}"
        val p = portfolios.find(req.portfolioId)
        if (p == null || p.status != "ACTIVE") gates += "An active paper portfolio is required"
        if (p != null && p.reconciliationStatus != "OK") gates += "Portfolio reconciliation must pass"
        if (req.allocationPercent <= BigDecimal.ZERO || req.allocationPercent > BigDecimal(100)) gates += "Allocation must be greater than 0 and at most 100 percent"
        val otherAllocations =
            jdbc
                .sql("select coalesce(sum(allocation_percent), 0) from strategy_activations where portfolio_id = :p and status = 'ACTIVE' and strategy_id <> :s")
                .param("p", req.portfolioId)
                .param("s", strategyId)
                .query(BigDecimal::class.java)
                .single()
        if (otherAllocations.add(req.allocationPercent) > BigDecimal(100)) gates += "Allocations on this portfolio would exceed 100 percent (${otherAllocations.toPlainString()}% already allocated)"
        if (!s.status.active && strategies.activeCount() >= StrategyService.MAX_ACTIVE) gates += "At most ${StrategyService.MAX_ACTIVE} strategies can be active"
        runCatching { profiles.global() }.onFailure { gates += "The risk profile could not be loaded" }
        if (req.mode == ActivationMode.AUTONOMOUS) {
            if (!req.disclosureAccepted || req.disclosureVersion != AutonomyDisclosure.VERSION) gates += "The current autonomous-mode disclosure (${AutonomyDisclosure.VERSION}) must be accepted"
        }
        if (gates.isNotEmpty()) {
            audit.recordIndependently(AuditCategory.AUTONOMY, "ACTIVATION_DENIED", AuditOutcome.BLOCKED, "Strategy", strategyId, mapOf("mode" to req.mode, "gates" to gates))
            throw Problems.unprocessable("activation-gates-failed", "Activation requirements are not met", mapOf("gates" to gates))
        }
        if (req.mode == ActivationMode.AUTONOMOUS) RecentAuth.require(clock.instant(), "enable-autonomous-mode")
        endActive(strategyId, "Replaced by new activation")
        val id = UUID.randomUUID()
        val now = clock.instant()
        val fingerprint = if (req.mode == ActivationMode.AUTONOMOUS) fingerprint(strategyId, versionId, req.portfolioId, req.allocationPercent) else null
        jdbc
            .sql(
                """
                insert into strategy_activations(id, strategy_id, version_id, content_hash, portfolio_id, mode, allocation_percent, backtest_id, disclosure_version,
                  authorized_at, authorized_session, fingerprint, status, created_at)
                values (:id, :s, :v, :h, :p, :m, :a, :b, :dv, :aa, :as, :fp, 'ACTIVE', :now)
                """.trimIndent(),
            ).param("id", id)
            .param("s", strategyId)
            .param("v", versionId)
            .param("h", v.contentHash)
            .param("p", req.portfolioId)
            .param("m", req.mode.name)
            .param("a", req.allocationPercent)
            .param("b", backtest!!.id)
            .param("dv", if (req.mode == ActivationMode.AUTONOMOUS) req.disclosureVersion else null)
            .param("aa", ts(if (req.mode == ActivationMode.AUTONOMOUS) now else null))
            .param("as", if (req.mode == ActivationMode.AUTONOMOUS) CurrentOwner.get().sessionId else null)
            .param("fp", fingerprint)
            .param("now", ts(now))
            .update()
        val target = if (req.mode == ActivationMode.AUTONOMOUS) StrategyStatus.ACTIVE_AUTONOMOUS else StrategyStatus.ACTIVE_RECOMMENDATION
        if (s.status != target) strategies.transition(strategyId, s.status, target, "Activated in ${req.mode} mode on portfolio ${req.portfolioId}")
        audit.record(
            AuditCategory.AUTONOMY,
            if (req.mode == ActivationMode.AUTONOMOUS) "AUTONOMY_AUTHORIZED" else "RECOMMENDATION_MODE_ACTIVATED",
            entityType = "Strategy",
            entityId = strategyId,
            details = mapOf("activationId" to id, "portfolioId" to req.portfolioId, "allocation" to req.allocationPercent, "hash" to v.contentHash, "backtestId" to backtest.id, "fingerprint" to fingerprint, "disclosure" to req.disclosureVersion),
        )
        return get(id)
    }

    /** Ends the active activation and pauses the strategy. */
    @Transactional
    fun deactivate(
        strategyId: UUID,
        reason: String,
        target: StrategyStatus = StrategyStatus.PAUSED,
    ) {
        val s = strategies.lock(strategyId)
        endActive(strategyId, reason)
        if (s.status.active && s.status != target) strategies.transition(strategyId, s.status, target, reason)
    }

    fun endActive(
        strategyId: UUID,
        reason: String,
    ) {
        jdbc
            .sql("update strategy_activations set status = 'ENDED', ended_at = :now, end_reason = :r where strategy_id = :s and status = 'ACTIVE'")
            .param("now", ts(clock.instant()))
            .param("r", reason.take(300))
            .param("s", strategyId)
            .update()
    }

    fun active(strategyId: UUID): Activation? =
        jdbc
            .sql("select * from strategy_activations where strategy_id = :s and status = 'ACTIVE'")
            .param("s", strategyId)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun activeAll(): List<Activation> = jdbc.sql("select * from strategy_activations where status = 'ACTIVE' order by created_at").query { rs, _ -> map(rs) }.list()

    fun get(id: UUID): Activation =
        jdbc
            .sql("select * from strategy_activations where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .single()

    fun history(strategyId: UUID): List<Activation> =
        jdbc
            .sql("select * from strategy_activations where strategy_id = :s order by created_at desc")
            .param("s", strategyId)
            .query { rs, _ -> map(rs) }
            .list()

    /**
     * Everything material to an autonomy authorization: strategy version hash, portfolio and its
     * cost model and shorting flag, allocation, and every applicable risk profile (FR-072).
     */
    fun fingerprint(
        strategyId: UUID,
        versionId: UUID,
        portfolioId: UUID,
        allocation: BigDecimal,
    ): String {
        val v = strategies.version(versionId)
        val p = portfolios.get(portfolioId)
        val material =
            listOf(
                "strategy=$strategyId",
                "hash=${v.contentHash}",
                "portfolio=$portfolioId",
                "portfolioStatus=${p.status}",
                "shorting=${p.shortingEnabled}",
                "costModel=${p.costModel}",
                "allocation=${allocation.stripTrailingZeros().toPlainString()}",
                "global=${profiles.global().limits}",
                "portfolioProfile=${profiles.limitsFor("PORTFOLIO", portfolioId)}",
                "strategyProfile=${profiles.limitsFor("STRATEGY", strategyId)}",
            ).joinToString("|")
        return Crypto.sha256Hex(material)
    }

    private fun map(rs: java.sql.ResultSet) =
        Activation(
            rs.uuid("id"),
            rs.uuid("strategy_id"),
            rs.uuid("version_id"),
            rs.getString("content_hash"),
            rs.uuid("portfolio_id"),
            ActivationMode.valueOf(rs.getString("mode")),
            rs.getBigDecimal("allocation_percent"),
            rs.uuid("backtest_id"),
            rs.getString("disclosure_version"),
            rs.instantOrNull("authorized_at"),
            rs.getString("fingerprint"),
            rs.getString("status"),
            rs.instant("created_at"),
            rs.instantOrNull("ended_at"),
            rs.getString("end_reason"),
        )
}
