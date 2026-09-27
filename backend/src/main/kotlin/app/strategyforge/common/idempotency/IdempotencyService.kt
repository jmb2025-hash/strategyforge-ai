package app.strategyforge.common.idempotency

import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.ts
import app.strategyforge.common.json.JsonConfig
import app.strategyforge.common.web.ApiException
import app.strategyforge.common.web.PROBLEM_BASE
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.problem
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Persisted idempotency for retryable consequential mutations (NFR-004, NFR-007).
 *
 * The key row is inserted in the SAME transaction as the mutation. A concurrent
 * request with the same key blocks on the unique index until the first commits
 * or rolls back, so at most one mutation executes per key. The stored response
 * status and body are replayed for retries.
 */
@Service
class IdempotencyService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }
    private val newTx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun <T : Any> execute(
        scope: String,
        key: String?,
        request: Any?,
        successStatus: HttpStatus = HttpStatus.OK,
        action: () -> T,
    ): ResponseEntity<Any> {
        val k = validateKey(key)
        val requestHash = AuditService.sha256(scope + "|" + JsonConfig.canonical(mapper, mapper.valueToTree(request ?: emptyMap<String, Any>())))
        try {
            return tx.execute {
                val inserted =
                    jdbc
                        .sql(
                            """
                            insert into idempotency_records(scope, idem_key, request_hash, status, created_at)
                            values (:scope, :key, :hash, 'IN_PROGRESS', :now)
                            on conflict (scope, idem_key) do nothing
                            """.trimIndent(),
                        ).param("scope", scope)
                        .param("key", k)
                        .param("hash", requestHash)
                        .param("now", ts(clock.instant()))
                        .update()
                if (inserted == 0) return@execute replay(scope, k, requestHash)
                val result = action()
                val body = mapper.writeValueAsString(result)
                complete(scope, k, successStatus.value(), body)
                ResponseEntity.status(successStatus).contentType(MediaType.APPLICATION_JSON).body(result as Any)
            }!!
        } catch (e: ApiException) {
            // Deterministic business rejections are stored so a retry observes the same outcome.
            // Auth/precondition/rate-limit/server failures are not stored: the caller may fix and retry.
            if (e.status.value() in STORABLE_FAILURES) {
                val body = mapper.writeValueAsString(problem(e.status, e.code, e.message, null, e.properties))
                newTx.execute {
                    jdbc
                        .sql(
                            """
                            insert into idempotency_records(scope, idem_key, request_hash, status, response_status, response_body, created_at, completed_at)
                            values (:scope, :key, :hash, 'COMPLETED', :status, :body, :now, :now)
                            on conflict (scope, idem_key) do nothing
                            """.trimIndent(),
                        ).param("scope", scope)
                        .param("key", k)
                        .param("hash", requestHash)
                        .param("status", e.status.value())
                        .param("body", body)
                        .param("now", ts(clock.instant()))
                        .update()
                }
            }
            throw e
        }
    }

    private fun complete(
        scope: String,
        key: String,
        status: Int,
        body: String,
    ) {
        jdbc
            .sql(
                "update idempotency_records set status='COMPLETED', response_status=:status, response_body=:body, completed_at=:now where scope=:scope and idem_key=:key",
            ).param("status", status)
            .param("body", body)
            .param("now", ts(clock.instant()))
            .param("scope", scope)
            .param("key", key)
            .update()
    }

    private fun replay(
        scope: String,
        key: String,
        requestHash: String,
    ): ResponseEntity<Any> {
        val row =
            jdbc
                .sql("select request_hash, status, response_status, response_body from idempotency_records where scope=:scope and idem_key=:key")
                .param("scope", scope)
                .param("key", key)
                .query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getInt(3).toString(), rs.getString(4)) }
                .single()
        if (row[0] != requestHash) {
            throw Problems.unprocessable("idempotency-key-reused", "Idempotency-Key was already used with a different request")
        }
        if (row[1] != "COMPLETED") throw Problems.conflict("idempotency-in-progress", "A request with this Idempotency-Key is still in progress")
        val status = HttpStatus.valueOf(row[2].toInt())
        val type = if (status.isError) MediaType.APPLICATION_PROBLEM_JSON else MediaType.APPLICATION_JSON
        return ResponseEntity
            .status(status)
            .contentType(type)
            .header(REPLAY_HEADER, "true")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(mapper.readTree(row[3]) as Any)
    }

    private fun validateKey(key: String?): String {
        if (key.isNullOrBlank()) throw Problems.badRequest("idempotency-key-required", "Idempotency-Key header is required for this operation")
        if (!KEY_PATTERN.matches(key)) throw Problems.badRequest("idempotency-key-invalid", "Idempotency-Key must be 8-128 characters of [A-Za-z0-9_-]")
        return key
    }

    companion object {
        const val HEADER = "Idempotency-Key"
        const val REPLAY_HEADER = "Idempotent-Replayed"
        val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
        private val STORABLE_FAILURES = setOf(400, 404, 409, 410, 422)

        @Suppress("unused")
        private const val TYPE_BASE = PROBLEM_BASE
    }
}
