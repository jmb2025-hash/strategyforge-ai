package app.strategyforge.providers

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.security.Crypto
import app.strategyforge.common.security.SecretCipher
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.Problems
import app.strategyforge.identity.RecentAuth
import app.strategyforge.safety.RealMoneyPolicy
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class ProviderKind { MARKET_DATA, AI, PUSH }

enum class SettingType { STRING, DECIMAL, INT, BOOLEAN, HTTPS_URL }

data class SettingSpec(
    val type: SettingType,
    val required: Boolean = false,
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
)

/**
 * Supported provider adapters. No kind can place orders; the database CHECK
 * constraint on provider_type mirrors this list (FR-113).
 */
enum class ProviderType(
    val kind: ProviderKind,
    val credentialRequired: Boolean,
    val credentialEnv: String?,
    val settings: Map<String, SettingSpec>,
) {
    REPLAY(ProviderKind.MARKET_DATA, false, null, emptyMap()),
    TWELVE_DATA(
        ProviderKind.MARKET_DATA,
        true,
        "MARKET_API_KEY",
        mapOf(
            "baseUrl" to SettingSpec(SettingType.HTTPS_URL),
            "requestsPerMinute" to SettingSpec(SettingType.INT, min = BigDecimal.ONE, max = BigDecimal(6000)),
            "timeoutSeconds" to SettingSpec(SettingType.INT, min = BigDecimal.ONE, max = BigDecimal(120)),
        ),
    ),
    ANTHROPIC(ProviderKind.AI, true, "ANTHROPIC_API_KEY", aiSettings(retrieval = true)),
    OPENAI(ProviderKind.AI, true, "OPENAI_API_KEY", aiSettings(retrieval = false)),
    GEMINI(ProviderKind.AI, true, "GEMINI_API_KEY", aiSettings(retrieval = false)),
    OPENROUTER(ProviderKind.AI, true, "OPENROUTER_API_KEY", aiSettings(retrieval = false)),
    FCM(
        ProviderKind.PUSH,
        true,
        "FCM_CREDENTIALS_PATH",
        mapOf(
            "projectId" to SettingSpec(SettingType.STRING, required = true),
            "applicationId" to SettingSpec(SettingType.STRING, required = true),
            "apiKey" to SettingSpec(SettingType.STRING, required = true),
            "senderId" to SettingSpec(SettingType.STRING, required = true),
            "baseUrl" to SettingSpec(SettingType.HTTPS_URL),
        ),
    ),
}

private fun aiSettings(retrieval: Boolean): Map<String, SettingSpec> =
    buildMap {
        put("model", SettingSpec(SettingType.STRING, required = true))
        put("baseUrl", SettingSpec(SettingType.HTTPS_URL))
        put("maxOutputTokens", SettingSpec(SettingType.INT, min = BigDecimal(64), max = BigDecimal(64000)))
        put("timeoutSeconds", SettingSpec(SettingType.INT, min = BigDecimal.ONE, max = BigDecimal(600)))
        // Pricing is required so cost ceilings are always verifiable (FR-037, D-010).
        put("inputPricePerMillionTokensUsd", SettingSpec(SettingType.DECIMAL, required = true, min = BigDecimal.ZERO, max = BigDecimal(1000)))
        put("outputPricePerMillionTokensUsd", SettingSpec(SettingType.DECIMAL, required = true, min = BigDecimal.ZERO, max = BigDecimal(1000)))
        if (retrieval) put("webSearchEnabled", SettingSpec(SettingType.BOOLEAN))
    }

data class CredentialStatus(
    val configured: Boolean,
    val source: String,
    val fingerprint: String?,
    val updatedAt: Instant?,
)

data class CapabilityView(
    val capability: String,
    val status: String,
    val detail: String?,
    val verifiedAt: Instant,
)

data class ProviderView(
    val id: UUID,
    val kind: String,
    val providerType: String,
    val displayName: String,
    val settings: Map<String, Any?>,
    /** Credentials are never returned after creation; only whether one exists and a short fingerprint. */
    val credential: CredentialStatus,
    val active: Boolean,
    val archivedAt: Instant?,
    val lastTestAt: Instant?,
    val lastTestStatus: String?,
    val lastTestDetail: String?,
    val capabilities: List<CapabilityView>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class ProviderCreate(
    val providerType: String,
    val displayName: String,
    val settings: Map<String, Any?> = emptyMap(),
    val credential: String? = null,
)

data class ProviderUpdate(
    val displayName: String,
    val settings: Map<String, Any?>,
)

data class CredentialUpdate(
    val credential: String,
)

/** A resolved provider configuration including its decrypted credential, for adapters only. Never serialized. */
data class ResolvedProvider(
    val id: UUID,
    val type: ProviderType,
    val displayName: String,
    val settings: Map<String, Any?>,
    val credential: String?,
) {
    override fun toString(): String = "ResolvedProvider(id=$id, type=$type, credential=${if (credential != null) "[REDACTED]" else "none"})"

    fun string(key: String): String? = settings[key]?.toString()

    fun int(
        key: String,
        default: Int,
    ): Int = settings[key]?.toString()?.toIntOrNull() ?: default

    fun decimal(key: String): BigDecimal? = settings[key]?.toString()?.let { BigDecimal(it) }

    fun bool(key: String): Boolean = settings[key]?.toString()?.toBoolean() ?: false
}

enum class TestStatus { OK, DEGRADED, FAILED, UNSUPPORTED }

data class CapabilityResult(
    val capability: String,
    val status: String,
    val detail: String?,
)

data class ProviderTestResult(
    val status: TestStatus,
    val detail: String,
    val capabilities: List<CapabilityResult> = emptyList(),
)

/** Implemented by each adapter family to run capability diagnostics (FR-004, "detect capabilities; never invent them"). */
interface ProviderTester {
    fun supports(type: ProviderType): Boolean

    fun test(provider: ResolvedProvider): ProviderTestResult
}

/** Published when the active market-data provider changes. */
data class MarketProviderActivated(
    val providerId: UUID,
    val type: ProviderType,
)

@Service
class ProviderService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val cipher: SecretCipher,
    private val audit: AuditService,
    private val clock: Clock,
    private val env: Environment,
    private val testers: org.springframework.beans.factory.ObjectProvider<ProviderTester>,
    private val events: org.springframework.context.ApplicationEventPublisher,
) {
    fun list(includeArchived: Boolean = false): List<ProviderView> =
        jdbc
            .sql("select * from provider_configurations where (:all or archived_at is null) order by created_at")
            .param("all", includeArchived)
            .query { rs, _ -> map(rs) }
            .list()

    fun get(id: UUID): ProviderView =
        jdbc
            .sql("select * from provider_configurations where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Provider", id) }

    @Transactional
    fun create(req: ProviderCreate): ProviderView {
        val type = parseType(req.providerType)
        val settings = validateSettings(type, req.settings)
        validateName(req.displayName)
        val id = UUID.randomUUID()
        val now = clock.instant()
        val (enc, fp) = encryptCredential(id, req.credential)
        jdbc
            .sql(
                """
                insert into provider_configurations(id, kind, provider_type, display_name, settings, credential_enc, credential_key_id,
                  credential_fingerprint, credential_updated_at, created_at, updated_at)
                values (:id, :kind, :type, :name, cast(:settings as jsonb), :enc, :kid, :fp, :cu, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("kind", type.kind.name)
            .param("type", type.name)
            .param("name", req.displayName.trim())
            .param("settings", mapper.writeValueAsString(settings))
            .param("enc", enc)
            .param("kid", enc?.let { cipher.keyId })
            .param("fp", fp)
            .param("cu", ts(if (enc != null) now else null))
            .param("now", ts(now))
            .update()
        audit.record(
            AuditCategory.PROVIDER,
            "PROVIDER_CREATED",
            entityType = "Provider",
            entityId = id,
            details = mapOf("type" to type.name, "displayName" to req.displayName, "credentialStored" to (enc != null), "settings" to settings),
        )
        return get(id)
    }

    @Transactional
    fun update(
        id: UUID,
        req: ProviderUpdate,
        ifMatch: String?,
    ): ProviderView {
        val current = get(id)
        ETags.require(ifMatch, current.version)
        val type = ProviderType.valueOf(current.providerType)
        val settings = validateSettings(type, req.settings)
        validateName(req.displayName)
        jdbc
            .sql("update provider_configurations set display_name = :n, settings = cast(:s as jsonb), updated_at = :now, version = version + 1 where id = :id and version = :v")
            .param("n", req.displayName.trim())
            .param("s", mapper.writeValueAsString(settings))
            .param("now", ts(clock.instant()))
            .param("id", id)
            .param("v", current.version)
            .update()
            .also { if (it == 0) throw Problems.preconditionFailed("Provider changed concurrently") }
        audit.record(AuditCategory.PROVIDER, "PROVIDER_UPDATED", entityType = "Provider", entityId = id, details = mapOf("before" to current.settings, "after" to settings))
        return get(id)
    }

    /** Key changes require recent authentication (section 15). */
    @Transactional
    fun setCredential(
        id: UUID,
        credential: String?,
    ): ProviderView {
        RecentAuth.require(clock.instant(), "provider-key-change")
        get(id)
        val (enc, fp) = encryptCredential(id, credential)
        jdbc
            .sql(
                """
                update provider_configurations set credential_enc = :enc, credential_key_id = :kid, credential_fingerprint = :fp,
                  credential_updated_at = :now, updated_at = :now, version = version + 1 where id = :id
                """.trimIndent(),
            ).param("enc", enc)
            .param("kid", enc?.let { cipher.keyId })
            .param("fp", fp)
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.PROVIDER, if (enc == null) "PROVIDER_CREDENTIAL_REMOVED" else "PROVIDER_CREDENTIAL_CHANGED", entityType = "Provider", entityId = id, details = mapOf("fingerprint" to fp))
        return get(id)
    }

    @Transactional
    fun activate(id: UUID): ProviderView {
        val p = get(id)
        if (p.archivedAt != null) throw Problems.conflict("provider-archived", "Archived providers cannot be activated")
        val type = ProviderType.valueOf(p.providerType)
        if (type.credentialRequired && !p.credential.configured) throw Problems.unprocessable("credential-missing", "Configure a credential before activating this provider")
        if (type.kind == ProviderKind.MARKET_DATA) {
            jdbc.sql("update provider_configurations set active = false, updated_at = :now where kind = 'MARKET_DATA' and active").param("now", ts(clock.instant())).update()
        }
        jdbc
            .sql("update provider_configurations set active = true, updated_at = :now, version = version + 1 where id = :id")
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.PROVIDER, "PROVIDER_ACTIVATED", entityType = "Provider", entityId = id, details = mapOf("type" to type.name))
        if (type.kind == ProviderKind.MARKET_DATA) events.publishEvent(MarketProviderActivated(id, type))
        return get(id)
    }

    @Transactional
    fun archive(id: UUID): ProviderView {
        val p = get(id)
        if (p.active && p.kind == ProviderKind.MARKET_DATA.name) throw Problems.conflict("provider-active", "Activate another market-data provider first")
        jdbc
            .sql("update provider_configurations set archived_at = :now, active = false, credential_enc = null, updated_at = :now, version = version + 1 where id = :id")
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.PROVIDER, "PROVIDER_ARCHIVED", entityType = "Provider", entityId = id)
        return get(id)
    }

    /** Runs capability diagnostics and records detected capabilities (never assumed). */
    fun test(id: UUID): ProviderView {
        val resolved = resolve(id)
        val tester = testers.orderedStream().toList().firstOrNull { it.supports(resolved.type) }
        val result =
            when {
                tester == null -> ProviderTestResult(TestStatus.UNSUPPORTED, "No diagnostics exist for ${resolved.type}")
                resolved.type.credentialRequired && resolved.credential == null -> ProviderTestResult(TestStatus.FAILED, "No credential configured")
                else -> runCatching { tester.test(resolved) }.getOrElse { ProviderTestResult(TestStatus.FAILED, "Diagnostics failed: ${it.javaClass.simpleName}") }
            }
        recordTest(id, result)
        return get(id)
    }

    @Transactional
    fun recordTest(
        id: UUID,
        result: ProviderTestResult,
    ) {
        val now = clock.instant()
        jdbc
            .sql("update provider_configurations set last_test_at = :now, last_test_status = :s, last_test_detail = :d where id = :id")
            .param("now", ts(now))
            .param("s", result.status.name)
            .param("d", result.detail.take(500))
            .param("id", id)
            .update()
        result.capabilities.forEach { c ->
            jdbc
                .sql(
                    """
                    insert into provider_capabilities(provider_id, capability, status, detail, verified_at) values (:id, :c, :s, :d, :now)
                    on conflict (provider_id, capability) do update set status = excluded.status, detail = excluded.detail, verified_at = excluded.verified_at
                    """.trimIndent(),
                ).param("id", id)
                .param("c", c.capability)
                .param("s", c.status)
                .param("d", c.detail?.take(500))
                .param("now", ts(now))
                .update()
        }
        audit.record(
            AuditCategory.PROVIDER,
            "PROVIDER_TESTED",
            if (result.status == TestStatus.OK) AuditOutcome.SUCCESS else AuditOutcome.FAILURE,
            entityType = "Provider",
            entityId = id,
            details = mapOf("status" to result.status.name, "detail" to result.detail, "capabilities" to result.capabilities.map { "${it.capability}=${it.status}" }),
        )
    }

    /** Decrypts the credential for an adapter call. Falls back to the environment variable named by the type. */
    fun resolve(id: UUID): ResolvedProvider =
        jdbc
            .sql("select * from provider_configurations where id = :id")
            .param("id", id)
            .query { rs, _ ->
                val type = ProviderType.valueOf(rs.getString("provider_type"))
                val enc = rs.getBytes("credential_enc")
                val credential = if (enc != null) cipher.decryptString(enc, "provider:$id") else environmentCredential(type)
                @Suppress("UNCHECKED_CAST")
                ResolvedProvider(id, type, rs.getString("display_name"), mapper.readValue(rs.getString("settings"), Map::class.java) as Map<String, Any?>, credential)
            }.optional()
            .orElseThrow { Problems.notFound("Provider", id) }

    fun activeMarketProviderId(): UUID? =
        jdbc
            .sql("select id from provider_configurations where kind = 'MARKET_DATA' and active and archived_at is null")
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun capabilities(id: UUID): List<CapabilityView> =
        jdbc
            .sql("select * from provider_capabilities where provider_id = :id order by capability")
            .param("id", id)
            .query { rs, _ -> CapabilityView(rs.getString("capability"), rs.getString("status"), rs.getString("detail"), rs.instant("verified_at")) }
            .list()

    private fun environmentCredential(type: ProviderType): String? {
        val name = type.credentialEnv ?: return null
        val v = env.getProperty(name)?.takeIf { it.isNotBlank() } ?: return null
        if (type == ProviderType.FCM) {
            val f = File(v)
            return if (f.isFile && f.canRead()) f.readText() else null
        }
        return v
    }

    private fun encryptCredential(
        id: UUID,
        credential: String?,
    ): Pair<ByteArray?, String?> {
        val c = credential?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        if (c.length > 20_000) throw Problems.badRequest("credential-too-large", "Credential is too large")
        return cipher.encryptString(c, "provider:$id") to Crypto.fingerprint(c)
    }

    private fun parseType(s: String): ProviderType = ProviderType.entries.firstOrNull { it.name == s } ?: throw Problems.badRequest("unsupported-provider", "Unsupported provider type '$s'. Supported: ${ProviderType.entries.map { it.name }}")

    private fun validateName(n: String) {
        if (n.isBlank() || n.length > 80) throw Problems.badRequest("invalid-name", "displayName must be 1-80 characters")
    }

    fun validateSettings(
        type: ProviderType,
        input: Map<String, Any?>,
    ): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        input.keys.forEach { k ->
            if (RealMoneyPolicy.isProhibitedField(k)) throw Problems.forbidden("real-money-prohibited", "Setting '$k' implies real-money functionality, which does not exist")
            if (k !in type.settings) throw Problems.badRequest("unknown-setting", "Unknown setting '$k' for ${type.name}. Allowed: ${type.settings.keys}")
        }
        type.settings.forEach { (k, spec) ->
            val v = input[k]
            if (v == null) {
                if (spec.required) throw Problems.badRequest("missing-setting", "Setting '$k' is required for ${type.name}")
                return@forEach
            }
            out[k] =
                when (spec.type) {
                    SettingType.STRING -> v.toString().trim().also { if (it.isEmpty() || it.length > 200) throw Problems.badRequest("invalid-setting", "'$k' must be 1-200 characters") }
                    SettingType.BOOLEAN -> (v as? Boolean) ?: throw Problems.badRequest("invalid-setting", "'$k' must be a boolean")
                    SettingType.INT -> {
                        val i = v.toString().toIntOrNull() ?: throw Problems.badRequest("invalid-setting", "'$k' must be an integer")
                        bounds(k, BigDecimal(i), spec)
                        i
                    }
                    SettingType.DECIMAL -> {
                        val d = runCatching { BigDecimal(v.toString()) }.getOrElse { throw Problems.badRequest("invalid-setting", "'$k' must be a decimal") }
                        bounds(k, d, spec)
                        d.toPlainString()
                    }
                    SettingType.HTTPS_URL -> {
                        val u = runCatching { URI(v.toString()) }.getOrElse { throw Problems.badRequest("invalid-setting", "'$k' must be a URL") }
                        val localHttp = u.scheme == "http" && (u.host == "localhost" || u.host == "127.0.0.1") && env.getProperty("strategyforge.providers.allow-local-http", Boolean::class.java, false)
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

    private fun map(rs: java.sql.ResultSet): ProviderView {
        val id = rs.uuid("id")
        val type = ProviderType.valueOf(rs.getString("provider_type"))
        val stored = rs.getBytes("credential_enc") != null
        val envCred = !stored && environmentCredential(type) != null
        @Suppress("UNCHECKED_CAST")
        return ProviderView(
            id = id,
            kind = rs.getString("kind"),
            providerType = type.name,
            displayName = rs.getString("display_name"),
            settings = mapper.readValue(rs.getString("settings"), Map::class.java) as Map<String, Any?>,
            credential =
                CredentialStatus(
                    configured = stored || envCred,
                    source =
                        if (stored) {
                            "STORED"
                        } else if (envCred) {
                            "ENVIRONMENT"
                        } else {
                            "NONE"
                        },
                    fingerprint = rs.getString("credential_fingerprint"),
                    updatedAt = rs.instantOrNull("credential_updated_at"),
                ),
            active = rs.getBoolean("active"),
            archivedAt = rs.instantOrNull("archived_at"),
            lastTestAt = rs.instantOrNull("last_test_at"),
            lastTestStatus = rs.getString("last_test_status"),
            lastTestDetail = rs.getString("last_test_detail"),
            capabilities = capabilities(id),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
            version = rs.getLong("version"),
        )
    }
}
