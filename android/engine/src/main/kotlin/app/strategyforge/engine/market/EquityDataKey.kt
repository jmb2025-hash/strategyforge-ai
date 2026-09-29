package app.strategyforge.engine.market

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.research.SecretStore

/**
 * The owner's Twelve Data key for US stock data (D-032). Like AI keys (D-030) it lives only in the
 * phone's key store; the database and audit log see a 12-character fingerprint at most. Changing
 * it needs a recent device-lock confirmation.
 */
class EquityDataKey(
    private val secrets: SecretStore,
    private val auth: RecentAuth,
    private val audit: AuditService,
) {
    @Volatile private var cached: String? = null

    @Volatile private var loaded = false

    fun get(): String? {
        if (!loaded) {
            cached = secrets.get(ALIAS)
            loaded = true
        }
        return cached
    }

    fun configured(): Boolean = get() != null

    fun fingerprint(): String? = get()?.let { Hashing.sha256Hex(it).take(12) }

    /** Stores ([key] set) or removes ([key] null) the key. */
    fun set(key: String?) {
        auth.require("change the stock data key")
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null && (clean.length !in 8..200 || clean.any { it.isWhitespace() })) {
            throw Problems.badRequest("invalid-key", "That does not look like a Twelve Data API key")
        }
        if (clean == null) secrets.delete(ALIAS) else secrets.put(ALIAS, clean)
        cached = clean
        loaded = true
        audit.record(
            AuditCategory.PROVIDER,
            if (clean == null) "MARKET_DATA_KEY_REMOVED" else "MARKET_DATA_KEY_CHANGED",
            entityType = "MarketDataProvider",
            entityId = ProviderType.TWELVE_DATA.name,
            details = mapOf("fingerprint" to fingerprint()),
        )
    }

    companion object {
        const val ALIAS = "market-twelve-data"
    }
}
