package app.strategyforge.android.core

import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.model.AiBudget
import app.strategyforge.android.core.state.BudgetForm
import app.strategyforge.android.core.state.ExportDataset
import app.strategyforge.android.core.state.ExportFormat
import app.strategyforge.android.core.state.ExportRequest
import app.strategyforge.android.core.state.TotpCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * FR-002 account security, FR-037 AI budget, FR-105 exports and FR-112 backups from the app:
 * requests hit the documented endpoints with the right bodies, headers and concurrency tokens.
 */
class AccountFeaturesTest {
    private val dispatcher = StandardTestDispatcher()
    private val h = Harness(dispatcher)

    @After
    fun close() = h.close()

    private val portfolio = "3f2b8c1e-8a52-4a9e-9f0e-2d9f0c7b1a11"

    @Test
    fun `FR-002 sessions, devices, TOTP and recovery codes use the identity endpoints`() =
        runTest(dispatcher) {
            h.server.enqueue(json("""[{"id":"s1","deviceName":"Pixel","current":true},{"id":"s2","deviceName":"Tablet","current":false,"revokedAt":null}]"""))
            h.server.enqueue(MockResponse().setResponseCode(204))
            h.server.enqueue(json("""{"count":1}"""))
            h.server.enqueue(json("""[{"id":"d1","name":"Pixel","platform":"ANDROID","pushEnabled":true,"pushTokenRegistered":true}]"""))
            h.server.enqueue(MockResponse().setResponseCode(204))
            h.server.enqueue(json("""{"secret":"JBSWY3DPEHPK3PXP","otpauthUri":"otpauth://totp/StrategyForge:owner?secret=JBSWY3DPEHPK3PXP"}"""))
            h.server.enqueue(MockResponse().setResponseCode(204))
            h.server.enqueue(json("""{"recoveryCodes":["aaaa-bbbb","cccc-dddd"],"notice":"Store offline"}"""))

            val sessions = h.repo.sessions()
            assertTrue(sessions.first().current)
            h.repo.revokeSession("s2")
            assertEquals(1, h.repo.revokeOtherSessions())
            assertTrue(
                h.repo
                    .devices()
                    .single()
                    .pushTokenRegistered,
            )
            h.repo.revokeDevice("d1")
            assertTrue(
                h.repo
                    .beginTotpSetup()
                    .otpauthUri
                    .startsWith("otpauth://totp/"),
            )
            h.repo.confirmTotp("123456")
            assertEquals(
                2,
                h.repo
                    .regenerateRecoveryCodes()
                    .recoveryCodes.size,
            )

            val requests = (1..8).map { h.server.takeRequest() }
            assertEquals(
                listOf(
                    "GET /v1/sessions",
                    "DELETE /v1/sessions/s2",
                    "POST /v1/sessions/revoke-others",
                    "GET /v1/devices",
                    "DELETE /v1/devices/d1",
                    "POST /v1/auth/totp/setup",
                    "POST /v1/auth/totp/confirm",
                    "POST /v1/auth/recovery-codes/regenerate",
                ),
                requests.map { "${it.method} ${it.path}" },
            )
            assertEquals("""{"code":"123456"}""", requests[6].body.readUtf8())
            assertTrue(requests[2].getHeader("Idempotency-Key")!!.isNotBlank())
        }

    @Test
    fun `FR-002 identifiers are validated before any request is sent`() =
        runTest(dispatcher) {
            try {
                h.repo.revokeSession("../auth/logout")
                fail("path traversal accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals(0, h.server.requestCount)
            }
            try {
                h.repo.verifyBackup("../../etc/passwd")
                fail("invalid backup name accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals(0, h.server.requestCount)
            }
        }

    @Test
    fun `FR-037 budget update sends If-Match and recent-auth failures surface as such`() =
        runTest(dispatcher) {
            val budget = """{"monthlyCostLimitUsd":"20.00","dailyRequestLimit":40,"maxOutputTokens":8000,"maxInputChars":60000,"monthCostUsd":"1.25","todayRequests":3,"version":4}"""
            h.server.enqueue(json(budget))
            h.server.enqueue(problem("recent-authentication-required", 403))
            val current = h.repo.aiBudget()
            assertEquals("1.25", current.monthCostUsd)
            val raise = BudgetForm.parse("50", "40", "8000", "60000").getOrThrow()
            assertTrue(raise.raises(current))
            try {
                h.repo.updateAiBudget(current, raise)
                fail("expected recent-auth failure")
            } catch (e: ApiError.Http) {
                assertEquals("recent-authentication-required", e.code)
            }
            h.server.takeRequest()
            val put = h.server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("\"4\"", put.getHeader("If-Match"))
            assertEquals("""{"monthlyCostLimitUsd":"50","dailyRequestLimit":40,"maxOutputTokens":8000,"maxInputChars":60000}""", put.body.readUtf8())
        }

    @Test
    fun `FR-037 budget form validation and raise detection`() {
        val current = AiBudget("20.00", 40, 8000, 60000)
        assertFalse(BudgetForm.parse("10.50", "20", "4000", "30000").getOrThrow().raises(current))
        assertTrue(BudgetForm.parse("20", "41", "8000", "60000").getOrThrow().raises(current))
        assertTrue(BudgetForm.parse("abc", "1", "1", "1").isFailure)
        assertTrue(BudgetForm.parse("1.001", "1", "1", "1").isFailure)
        assertTrue(BudgetForm.parse("-1", "1", "1", "1").isFailure)
        assertTrue(BudgetForm.parse("5", "1", "0", "1").isFailure)
        assertEquals(BigDecimal("12.5"), BudgetForm.parse(" 12.5 ", "1", "1", "1").getOrThrow().monthlyCostLimitUsd)
        assertEquals("123456", TotpCode.normalize("123 456"))
        assertNull(TotpCode.normalize("12345"))
        assertNull(TotpCode.normalize("12a456"))
    }

    @Test
    fun `FR-112 backups are listed, created and verified`() =
        runTest(dispatcher) {
            val name = "strategyforge-20260928-031700-ab12.sfbk"
            h.server.enqueue(json("""{"items":[{"name":"$name","sizeBytes":2048,"modifiedAt":"2026-09-28T03:17:05Z"}]}"""))
            h.server.enqueue(json("""{"file":{"name":"$name","sizeBytes":2048},"sha256":"abc","schemaVersion":"9","tables":57,"rows":217,"fingerprint":{}}""", 201))
            h.server.enqueue(json("""{"name":"$name","valid":false,"tables":0,"rows":0,"error":"Backup failed authentication"}"""))
            assertEquals(
                2048,
                h.repo
                    .backups()
                    .single()
                    .sizeBytes,
            )
            assertEquals("9", h.repo.createBackup().schemaVersion)
            val v = h.repo.verifyBackup(name)
            assertFalse(v.valid)
            assertEquals("Backup failed authentication", v.error)
            assertEquals("GET /v1/backups", h.server.takeRequest().let { "${it.method} ${it.path}" })
            assertEquals("POST /v1/backups", h.server.takeRequest().let { "${it.method} ${it.path}" })
            assertEquals("POST /v1/backups/$name/verify", h.server.takeRequest().let { "${it.method} ${it.path}" })
        }

    @Test
    fun `FR-105 exports download the raw file with its name and reconciliation header`() =
        runTest(dispatcher) {
            val csv = "executionId,orderId\r\nTOTAL,commission,4.50\r\n"
            h.server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/csv;charset=UTF-8")
                    .setHeader("Content-Disposition", "attachment; filename=\"strategyforge-executions-v1.csv\"")
                    .setHeader("X-StrategyForge-Reconciled", "true")
                    .setBody(csv),
            )
            h.server.enqueue(problem("not-found", 404, "Portfolio was not found"))
            val req = ExportRequest.of(ExportDataset.EXECUTIONS, ExportFormat.CSV, portfolio).getOrThrow()
            val d = h.repo.export(req)
            assertEquals(csv, d.bytes.toString(Charsets.UTF_8))
            assertEquals("strategyforge-executions-v1.csv", d.fileName)
            assertEquals("true", d.headers["X-StrategyForge-Reconciled"])
            assertEquals("/v1/exports/executions?format=csv&portfolioId=$portfolio", h.server.takeRequest().path)
            try {
                h.repo.export(req)
                fail("expected error")
            } catch (e: ApiError.Http) {
                assertEquals(404, e.status)
                assertEquals("Portfolio was not found", e.detail)
            }
        }

    @Test
    fun `FR-105 export requests require a portfolio except for the audit log`() {
        assertTrue(ExportRequest.of(ExportDataset.LEDGER, ExportFormat.JSON, null).isFailure)
        assertTrue(ExportRequest.of(ExportDataset.LEDGER, ExportFormat.JSON, "not-a-uuid&x=1").isFailure)
        val audit = ExportRequest.of(ExportDataset.AUDIT, ExportFormat.CSV, portfolio).getOrThrow()
        assertEquals("/v1/exports/audit?format=csv", audit.path)
        assertEquals("strategyforge-audit-v1.csv", audit.fileName)
        assertEquals("/v1/exports/ledger?format=json&portfolioId=$portfolio", ExportRequest.of(ExportDataset.LEDGER, ExportFormat.JSON, portfolio).getOrThrow().path)
    }
}
