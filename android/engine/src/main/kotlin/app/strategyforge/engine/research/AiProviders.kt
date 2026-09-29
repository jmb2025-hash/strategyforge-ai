package app.strategyforge.engine.research

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.market.TestStatus
import app.strategyforge.engine.safety.RealMoneyPolicy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Where API keys live. The app implements this with the Android Keystore; keys never enter the
 * database, logs, backups or exports (NFR-004). Tests use [InMemorySecretStore].
 */
interface SecretStore {
    fun put(
        alias: String,
        secret: String,
    )

    fun get(alias: String): String?

    fun delete(alias: String)
}

class InMemorySecretStore : SecretStore {
    private val m = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun put(
        alias: String,
        secret: String,
    ) {
        m[alias] = secret
    }

    override fun get(alias: String): String? = m[alias]

    override fun delete(alias: String) {
        m.remove(alias)
    }
}

enum class SettingType { STRING, DECIMAL, INT, BOOLEAN, HTTPS_URL }

data class SettingSpec(
    val type: SettingType,
    val required: Boolean = false,
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
)

private fun aiSettings(retrieval: Boolean): Map<String, SettingSpec> =
    buildMap {
        put("model", SettingSpec(SettingType.STRING, required = true))
        put("baseUrl", SettingSpec(SettingType.HTTPS_URL))
        put("maxOutputTokens", SettingSpec(SettingType.INT, min = BigDecimal(64), max = BigDecimal(64000)))
        put("timeoutSeconds", SettingSpec(SettingType.INT, min = BigDecimal.ONE, max = BigDecimal(600)))
        // Pricing is required so cost ceilings are always verifiable (FR-037, D-010); 0 for free tiers.
        put("inputPricePerMillionTokensUsd", SettingSpec(SettingType.DECIMAL, required = true, min = BigDecimal.ZERO, max = BigDecimal(1000)))
        put("outputPricePerMillionTokensUsd", SettingSpec(SettingType.DECIMAL, required = true, min = BigDecimal.ZERO, max = BigDecimal(1000)))
        if (retrieval) {
            put("webSearchEnabled", SettingSpec(SettingType.BOOLEAN))
            // Searches are billed separately; a price is required before retrieval can run (D-010).
            put("webSearchPricePerThousandUsd", SettingSpec(SettingType.DECIMAL, min = BigDecimal.ZERO, max = BigDecimal(1000)))
            put("maxSearchesPerRequest", SettingSpec(SettingType.INT, min = BigDecimal.ONE, max = BigDecimal(20)))
            put("serverSideFallback", SettingSpec(SettingType.BOOLEAN))
        }
    }

/**
 * AI providers the phone can call directly (D-027). Gemini is the default because its free tier
 * needs only a key; the others are optional. [presets] pre-fill the setup form and are always
 * shown to the owner, who confirms or changes them (prices included).
 */
enum class AiProviderType(
    val label: String,
    val settings: Map<String, SettingSpec>,
    val presets: Map<String, String>,
    val keyUrl: String,
) {
    GEMINI(
        "Google Gemini (free tier available)",
        aiSettings(retrieval = true),
        // Google Search grounding is on by default; on the free tier it is within the daily free quota.
        mapOf(
            "model" to "gemini-flash-latest",
            "inputPricePerMillionTokensUsd" to "0",
            "outputPricePerMillionTokensUsd" to "0",
            "webSearchEnabled" to "true",
            "webSearchPricePerThousandUsd" to "0",
        ),
        "https://aistudio.google.com/apikey",
    ),
    OPENROUTER(
        "OpenRouter",
        aiSettings(retrieval = false),
        mapOf("model" to "openrouter/auto"),
        "https://openrouter.ai/keys",
    ),
    OPENAI("OpenAI", aiSettings(retrieval = false), mapOf("model" to "gpt-5-mini"), "https://platform.openai.com/api-keys"),
    ANTHROPIC("Anthropic Claude", aiSettings(retrieval = true), mapOf("model" to "claude-opus-5"), "https://console.anthropic.com/settings/keys"),
}

/** A provider with its key, for adapters only. Never persisted, serialized or logged. */
data class AiProviderConfig(
    val id: UUID,
    val type: AiProviderType,
    val displayName: String,
    val settings: Map<String, String>,
    val key: String?,
) {
    override fun toString(): String = "AiProviderConfig(id=$id, type=$type, key=${if (key != null) "[REDACTED]" else "none"})"

    fun string(k: String): String? = settings[k]

    fun int(
        k: String,
        default: Int,
    ): Int = settings[k]?.toIntOrNull() ?: default

    fun decimal(k: String): BigDecimal? = settings[k]?.let { BigDecimal(it) }

    fun bool(k: String): Boolean = settings[k]?.toBoolean() ?: false

    /** Web search is on for Gemini unless turned off (D-034); Anthropic needs it switched on. */
    fun webSearchEnabled(): Boolean =
        when (type) {
            AiProviderType.GEMINI -> settings["webSearchEnabled"] != "false"
            AiProviderType.ANTHROPIC -> bool("webSearchEnabled")
            else -> false
        }

    /**
     * Price per thousand web searches. A Gemini provider on free-tier prices (0 per token) that
     * predates the web-search setting is treated as free, like its tokens; otherwise the price must
     * be configured so cost ceilings stay verifiable (D-010).
     */
    fun webSearchPrice(): BigDecimal? =
        decimal("webSearchPricePerThousandUsd")
            ?: if (type == AiProviderType.GEMINI && decimal("inputPricePerMillionTokensUsd")?.signum() == 0) BigDecimal.ZERO else null
}

data class AiProviderView(
    val id: UUID,
    val providerType: AiProviderType,
    val displayName: String,
    val settings: Map<String, String>,
    /** Keys are never shown again after entry; only whether one exists and a short fingerprint. */
    val keyConfigured: Boolean,
    val keyFingerprint: String?,
    val active: Boolean,
    val lastTestStatus: String?,
    val lastTestDetail: String?,
    val lastTestAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * AI provider configuration (FR-030). Settings are validated against each provider's spec; keys
 * go to the [SecretStore] and changing one needs a recent device-lock confirmation (section 15).
 */
class AiProviderService(
    private val db: Db,
    private val secrets: SecretStore,
    private val clients: AiClients,
    private val audit: AuditService,
    private val auth: RecentAuth,
    private val clock: Clock,
    /** Tests only: allows plain-http localhost endpoints (recorded-response servers). Never set by the app. */
    private val allowLocalHttp: Boolean = false,
) {
    private val settingsSerializer = MapSerializer(String.serializer(), String.serializer())

    fun list(includeInactive: Boolean = false): List<AiProviderView> =
        db
            .sql("select * from ai_providers where (:all = 1 or active = 1) order by created_at")
            .param("all", includeInactive)
            .list { map(it) }

    fun get(id: UUID): AiProviderView =
        db
            .sql("select * from ai_providers where id = :id")
            .param("id", id)
            .firstOrNull { map(it) } ?: throw Problems.notFound("AI provider", id)

    fun create(
        type: AiProviderType,
        displayName: String,
        settings: Map<String, String>,
        key: String?,
    ): AiProviderView {
        validateName(displayName)
        val clean = validateSettings(type, settings)
        val id = UUID.randomUUID()
        val now = clock.instant()
        val fp = key?.let { storeKey(id, it) }
        db
            .sql(
                """
                insert into ai_providers(id, provider_type, display_name, settings, key_alias, key_fingerprint, active, created_at, updated_at)
                values (:id, :t, :n, :s, :a, :fp, 1, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("t", type.name)
            .param("n", displayName.trim())
            .param("s", EngineJson.encodeToString(settingsSerializer, clean))
            .param("a", fp?.let { alias(id) })
            .param("fp", fp)
            .param("now", now)
            .update()
        audit.record(AuditCategory.PROVIDER, "PROVIDER_CREATED", entityType = "AiProvider", entityId = id, details = mapOf("type" to type.name, "displayName" to displayName, "keyStored" to (fp != null), "settings" to clean))
        return get(id)
    }

    fun update(
        id: UUID,
        displayName: String,
        settings: Map<String, String>,
    ): AiProviderView {
        val current = get(id)
        validateName(displayName)
        val clean = validateSettings(current.providerType, settings)
        db
            .sql("update ai_providers set display_name = :n, settings = :s, updated_at = :now where id = :id")
            .param("n", displayName.trim())
            .param("s", EngineJson.encodeToString(settingsSerializer, clean))
            .param("now", clock.instant())
            .param("id", id)
            .update()
        audit.record(AuditCategory.PROVIDER, "PROVIDER_UPDATED", entityType = "AiProvider", entityId = id, details = mapOf("before" to current.settings, "after" to clean))
        return get(id)
    }

    /** Sets or removes (null) the key; needs a recent device-lock confirmation. */
    fun setKey(
        id: UUID,
        key: String?,
    ): AiProviderView {
        auth.require("change an AI provider key")
        get(id)
        val fp =
            if (key == null) {
                secrets.delete(alias(id))
                null
            } else {
                storeKey(id, key)
            }
        // A new or removed key makes the previous test result meaningless, so it is cleared.
        db
            .sql(
                "update ai_providers set key_alias = :a, key_fingerprint = :fp, last_test_status = null, last_test_detail = null, last_test_at = null, updated_at = :now where id = :id",
            ).param("a", fp?.let { alias(id) })
            .param("fp", fp)
            .param("now", clock.instant())
            .param("id", id)
            .update()
        audit.record(AuditCategory.PROVIDER, if (fp == null) "PROVIDER_CREDENTIAL_REMOVED" else "PROVIDER_CREDENTIAL_CHANGED", entityType = "AiProvider", entityId = id, details = mapOf("fingerprint" to fp))
        return get(id)
    }

    fun setActive(
        id: UUID,
        active: Boolean,
    ): AiProviderView {
        get(id)
        db
            .sql("update ai_providers set active = :a, updated_at = :now where id = :id")
            .param("a", active)
            .param("now", clock.instant())
            .param("id", id)
            .update()
        audit.record(AuditCategory.PROVIDER, if (active) "PROVIDER_ACTIVATED" else "PROVIDER_DEACTIVATED", entityType = "AiProvider", entityId = id)
        return get(id)
    }

    /** Resolves settings and key for an adapter call. */
    fun resolve(id: UUID): AiProviderConfig {
        val v = get(id)
        val key =
            db
                .sql("select key_alias from ai_providers where id = :id")
                .param("id", id)
                .single { it.string("key_alias") }
                ?.let { secrets.get(it) }
        return AiProviderConfig(v.id, v.providerType, v.displayName, v.settings, key)
    }

    /** One minimal request proves the key and model (FR-004); cited retrieval stays unverified until a run returns citations. */
    fun test(id: UUID): AiProviderView {
        val p = resolve(id)
        val (status, detail) =
            if (p.key == null) {
                TestStatus.FAILED to "No API key configured"
            } else {
                try {
                    ping(p)
                } catch (e: AiException) {
                    // A retired or unavailable Gemini model: pick one this key can use and try again (D-039).
                    val replacement = if (e.httpStatus == 404 && p.type == AiProviderType.GEMINI) geminiReplacement(p) else null
                    if (replacement == null) {
                        TestStatus.FAILED to "${e.failure}: ${e.message}"
                    } else {
                        val old = p.string("model")
                        update(id, p.displayName, get(id).settings + ("model" to replacement))
                        try {
                            val (s, d) = ping(resolve(id))
                            s to "Model $old is not available to this key, so the app switched to $replacement. $d"
                        } catch (e2: AiException) {
                            TestStatus.FAILED to "Switched from $old to $replacement, but it failed too. ${e2.failure}: ${e2.message}"
                        }
                    }
                }
            }
        db
            .sql("update ai_providers set last_test_status = :s, last_test_detail = :d, last_test_at = :now where id = :id")
            .param("s", status.name)
            .param("d", detail.take(500))
            .param("now", clock.instant())
            .param("id", id)
            .update()
        audit.record(AuditCategory.PROVIDER, "PROVIDER_TESTED", entityType = "AiProvider", entityId = id, details = mapOf("status" to status, "detail" to detail))
        return get(id)
    }

    private fun ping(p: AiProviderConfig): Pair<TestStatus, String> {
        val r = clients.forType(p.type).complete(p, AiRequest("Reply with the single word OK.", "Connectivity check.", TEST_TOKENS, false))
        return TestStatus.OK to "Model ${r.model} answered (${r.inputTokens} input / ${r.outputTokens} output tokens)"
    }

    private fun geminiReplacement(p: AiProviderConfig): String? {
        val gemini = clients.forType(p.type) as? GeminiClient ?: return null
        val names = runCatching { gemini.availableModels(p) }.getOrNull() ?: return null
        return AiClients.pickGeminiModel(names)?.takeIf { it != p.string("model") }
    }

    private fun storeKey(
        id: UUID,
        key: String,
    ): String {
        val k = key.trim()
        if (k.length < 8 || k.length > 500 || k.any { it.isWhitespace() }) throw Problems.badRequest("invalid-key", "The API key does not look valid")
        secrets.put(alias(id), k)
        return Hashing.sha256Hex(k).take(12)
    }

    private fun alias(id: UUID) = "ai-provider-$id"

    private fun validateName(n: String) {
        if (n.isBlank() || n.length > 80) throw Problems.badRequest("invalid-name", "Name must be 1-80 characters")
    }

    fun validateSettings(
        type: AiProviderType,
        input: Map<String, String>,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        input.keys.forEach { k ->
            if (RealMoneyPolicy.isProhibitedField(k)) throw Problems.forbidden("real-money-prohibited", "Setting '$k' implies real-money functionality, which does not exist")
            if (k !in type.settings) throw Problems.badRequest("unknown-setting", "Unknown setting '$k' for ${type.name}. Allowed: ${type.settings.keys}")
        }
        type.settings.forEach { (k, spec) ->
            val v = input[k]?.trim()?.takeIf { it.isNotEmpty() }
            if (v == null) {
                if (spec.required) throw Problems.badRequest("missing-setting", "Setting '$k' is required for ${type.name}")
                return@forEach
            }
            out[k] =
                when (spec.type) {
                    SettingType.STRING -> v.also { if (it.length > 200) throw Problems.badRequest("invalid-setting", "'$k' must be 1-200 characters") }
                    SettingType.BOOLEAN -> v.toBooleanStrictOrNull()?.toString() ?: throw Problems.badRequest("invalid-setting", "'$k' must be true or false")
                    SettingType.INT -> {
                        val i = v.toIntOrNull() ?: throw Problems.badRequest("invalid-setting", "'$k' must be an integer")
                        bounds(k, BigDecimal(i), spec)
                        i.toString()
                    }
                    SettingType.DECIMAL -> {
                        val d = v.toBigDecimalOrNull() ?: throw Problems.badRequest("invalid-setting", "'$k' must be a decimal")
                        bounds(k, d, spec)
                        d.toPlainString()
                    }
                    SettingType.HTTPS_URL -> {
                        val u = runCatching { URI(v) }.getOrElse { throw Problems.badRequest("invalid-setting", "'$k' must be a URL") }
                        val localHttp = allowLocalHttp && u.scheme == "http" && (u.host == "localhost" || u.host == "127.0.0.1")
                        if (u.scheme != "https" && !localHttp) throw Problems.badRequest("invalid-setting", "'$k' must use https")
                        u.toString().trimEnd('/')
                    }
                }
        }
        return out
    }

    private fun bounds(
        k: String,
        v: BigDecimal,
        spec: SettingSpec,
    ) {
        if ((spec.min != null && v < spec.min) || (spec.max != null && v > spec.max)) {
            throw Problems.badRequest("invalid-setting", "'$k' must be between ${spec.min} and ${spec.max}")
        }
    }

    private fun map(rs: Row) =
        AiProviderView(
            rs.uuid("id"),
            AiProviderType.valueOf(rs.str("provider_type")),
            rs.str("display_name"),
            EngineJson.decodeFromString(settingsSerializer, rs.str("settings")),
            rs.string("key_alias") != null,
            rs.string("key_fingerprint"),
            rs.bool("active"),
            rs.string("last_test_status"),
            rs.string("last_test_detail"),
            rs.instantOrNull("last_test_at"),
            rs.instant("created_at"),
            rs.instant("updated_at"),
        )

    companion object {
        const val TEST_TOKENS = 1024
    }
}
