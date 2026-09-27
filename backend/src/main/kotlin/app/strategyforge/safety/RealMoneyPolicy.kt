package app.strategyforge.safety

/**
 * Version 1 safety boundary: real-money trading does not exist (FR-113, section 15).
 *
 * There is no brokerage module, credential field, route or feature toggle. This
 * object centralizes the invariants that other layers assert:
 *  - the process refuses to start if REAL_MONEY_TRADING_ENABLED is anything but "false";
 *  - every order is executed by the internal paper simulator only;
 *  - every portfolio is a PAPER account (also enforced by database CHECK constraints);
 *  - requests to brokerage-shaped routes are rejected and audited;
 *  - strategy files and settings containing live-trading fields are rejected.
 */
object RealMoneyPolicy {
    const val EXECUTION_VENUE = "PAPER_SIMULATOR"
    const val ACCOUNT_TYPE = "PAPER"
    const val ENV_FLAG = "REAL_MONEY_TRADING_ENABLED"

    /** Field names that imply live execution; rejected in strategies, settings and provider configuration. */
    val PROHIBITED_FIELD_PATTERN =
        Regex("(?i)^(live[-_]?trading|real[-_]?money|realmoney|broker(age)?([-_]?(id|account|key|secret|url|credentials?))?|execution[-_]?venue|live[-_]?account|account[-_]?number|routing|order[-_]?routing|withdraw(al)?|deposit|wallet|custody|margin[-_]?account)$")

    /** Route segments that imply real-money functionality; rejected with 403 and audited. */
    val PROHIBITED_ROUTE =
        Regex("(?i)^/v\\d+/(.*/)?(live|live-trading|broker|brokers|brokerage|real-money|realmoney|deposit|deposits|withdraw|withdrawals|wallet|wallets|custody|funding-source)(/.*)?$")

    class RealMoneyProhibitedException(
        message: String,
    ) : IllegalStateException(message)

    fun assertEnvironmentSafe(env: Map<String, String>) {
        val v = env[ENV_FLAG] ?: return
        assertFlagSafe(v)
    }

    fun assertFlagSafe(value: String) {
        if (value.trim() != "false") {
            throw RealMoneyProhibitedException(
                "$ENV_FLAG must be 'false'. Real-money trading is not available in StrategyForge Version 1 and cannot be enabled.",
            )
        }
    }

    fun isProhibitedField(name: String): Boolean = PROHIBITED_FIELD_PATTERN.matches(name)
}
