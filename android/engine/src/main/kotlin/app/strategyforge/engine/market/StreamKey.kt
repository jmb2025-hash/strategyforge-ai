package app.strategyforge.engine.market

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.research.SecretStore

/**
 * The owner's Alpaca market-data key pair for real-time US stock prices (D-056). Like the Twelve
 * Data key it lives only in the phone's key store; the database and audit log see a fingerprint
 * of the key ID at most. Changing it needs a recent device-lock confirmation.
 */
class StreamKey(
    private val secrets: SecretStore,
    private val auth: RecentAuth,
    private val audit: AuditService,
) {
    @Volatile private var cached: Pair<String, String>? = null

    @Volatile private var loaded = false

    fun get(): Pair<String, String>? {
        if (!loaded) {
            val id = secrets.get(ID_ALIAS)
            val secret = secrets.get(SECRET_ALIAS)
            cached = if (id != null && secret != null) id to secret else null
            loaded = true
        }
        return cached
    }

    fun configured(): Boolean = get() != null

    fun fingerprint(): String? = get()?.let { Hashing.sha256Hex(it.first).take(12) }

    /** Stores both parts, or removes the key when both are null. */
    fun set(
        keyId: String?,
        secret: String?,
    ) {
        auth.require("change the stock streaming key")
        val id = keyId?.trim()?.takeIf { it.isNotEmpty() }
        val sec = secret?.trim()?.takeIf { it.isNotEmpty() }
        if ((id == null) != (sec == null)) throw Problems.badRequest("invalid-key", "Enter both the Alpaca API key ID and its secret")
        if (id != null && (id.length !in 10..64 || !id.all { it.isLetterOrDigit() })) throw Problems.badRequest("invalid-key", "That does not look like an Alpaca API key ID")
        if (sec != null && (sec.length !in 20..128 || sec.any { it.isWhitespace() })) throw Problems.badRequest("invalid-key", "That does not look like an Alpaca secret key")
        if (id == null || sec == null) {
            secrets.delete(ID_ALIAS)
            secrets.delete(SECRET_ALIAS)
            cached = null
        } else {
            secrets.put(ID_ALIAS, id)
            secrets.put(SECRET_ALIAS, sec)
            cached = id to sec
        }
        loaded = true
        audit.record(
            AuditCategory.PROVIDER,
            if (id == null) "STREAM_KEY_REMOVED" else "STREAM_KEY_CHANGED",
            entityType = "MarketDataProvider",
            entityId = "ALPACA_IEX",
            details = mapOf("fingerprint" to fingerprint()),
        )
    }

    companion object {
        const val ID_ALIAS = "market-alpaca-key-id"
        const val SECRET_ALIAS = "market-alpaca-secret"
    }
}
