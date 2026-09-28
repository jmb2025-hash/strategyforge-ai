package app.strategyforge.android.core.state

import app.strategyforge.android.core.model.AiBudget
import java.math.BigDecimal

/** Export datasets served by `/v1/exports/{dataset}` (FR-105). Only the audit log is not per portfolio. */
enum class ExportDataset(
    val path: String,
    val label: String,
    val perPortfolio: Boolean,
) {
    LEDGER("ledger", "Ledger entries", true),
    EXECUTIONS("executions", "Executions (fills)", true),
    ORDERS("orders", "Orders", true),
    RECOMMENDATIONS("recommendations", "Recommendations", true),
    RISK("risk-evaluations", "Risk evaluations", true),
    AUDIT("audit", "Audit log", false),
}

enum class ExportFormat(
    val param: String,
    val mimeType: String,
) {
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
}

/** A validated export request; [fileName] is the suggested name for the save dialog. */
data class ExportRequest(
    val dataset: ExportDataset,
    val format: ExportFormat,
    val portfolioId: String?,
) {
    val path: String
        get() = "/v1/exports/${dataset.path}?format=${format.param}" + (portfolioId?.let { "&portfolioId=$it" } ?: "")

    val fileName: String get() = "strategyforge-${dataset.path}-v1.${format.param}"

    companion object {
        private val UUID_RE = Regex("^[0-9a-fA-F-]{36}$")

        /** Returns the request, or an owner-facing reason it cannot be made. */
        fun of(
            dataset: ExportDataset,
            format: ExportFormat,
            portfolioId: String?,
        ): Result<ExportRequest> =
            when {
                dataset.perPortfolio && portfolioId.isNullOrBlank() -> Result.failure(IllegalArgumentException("Choose a portfolio for this export"))
                dataset.perPortfolio && !UUID_RE.matches(portfolioId!!) -> Result.failure(IllegalArgumentException("Invalid portfolio"))
                else -> Result.success(ExportRequest(dataset, format, if (dataset.perPortfolio) portfolioId else null))
            }
    }
}

/** Parsed AI budget edit (FR-037). Raising a ceiling needs recent authentication on the backend. */
data class BudgetForm(
    val monthlyCostLimitUsd: BigDecimal,
    val dailyRequestLimit: Int,
    val maxOutputTokens: Int,
    val maxInputChars: Int,
) {
    /** True when any ceiling is higher than the current one, so the owner is told re-authentication applies. */
    fun raises(current: AiBudget): Boolean =
        monthlyCostLimitUsd > BigDecimal(current.monthlyCostLimitUsd) ||
            dailyRequestLimit > current.dailyRequestLimit ||
            maxOutputTokens > current.maxOutputTokens ||
            maxInputChars > current.maxInputChars

    companion object {
        fun parse(
            monthly: String,
            daily: String,
            maxOutput: String,
            maxInput: String,
        ): Result<BudgetForm> {
            val m = monthly.trim().toBigDecimalOrNull()
            val d = daily.trim().toIntOrNull()
            val o = maxOutput.trim().toIntOrNull()
            val i = maxInput.trim().toIntOrNull()
            return when {
                m == null || m.signum() < 0 || m.scale() > 2 -> Result.failure(IllegalArgumentException("Monthly limit must be an amount in USD with at most 2 decimals"))
                d == null || d < 0 -> Result.failure(IllegalArgumentException("Daily request limit must be a whole number"))
                o == null || o <= 0 -> Result.failure(IllegalArgumentException("Maximum output tokens must be a positive whole number"))
                i == null || i <= 0 -> Result.failure(IllegalArgumentException("Maximum input characters must be a positive whole number"))
                else -> Result.success(BudgetForm(m, d, o, i))
            }
        }
    }
}

/** TOTP codes are exactly six digits; spaces from authenticator apps are ignored. */
object TotpCode {
    fun normalize(input: String): String? = input.filterNot(Char::isWhitespace).takeIf { it.matches(Regex("^\\d{6}$")) }
}
