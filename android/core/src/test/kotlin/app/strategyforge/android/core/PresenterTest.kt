package app.strategyforge.android.core

import app.strategyforge.android.core.state.AccessPresenter
import app.strategyforge.android.core.state.AccessStep
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.EmergencyPresenter
import app.strategyforge.android.core.state.RecommendationPresenter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ViewModel logic for FR-063..FR-065, FR-075 and access (FR-001/FR-002) on the client side. */
class PresenterTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val h = Harness(dispatcher)

    @After
    fun close() = h.close()

    private fun decision(status: String) = """{"recommendation":${RECOMMENDATION_JSON.replace("\"PENDING\"", "\"$status\"")},"orderId":"o-1","orderStatus":"PENDING","detail":"Paper order o-1 created"}"""

    @Test
    fun `accept sends the action token and a single modification, and duplicate taps create one request`() =
        runTest(dispatcher) {
            val p = RecommendationPresenter(scope, h.repo, "11111111-1111-1111-1111-111111111111", newKey = { "accept-key" })
            h.server.enqueue(json(detailJson()))
            p.load()
            advanceUntilIdle()
            h.server.takeRequest()
            p.setQuantity("0.005")
            assertNull(p.state.value.validation)
            h.server.enqueue(json(decision("MODIFIED")))
            p.accept()
            p.accept() // duplicate tap while running
            advanceUntilIdle()
            val sent = h.server.takeRequest()
            assertEquals("/v1/recommendations/11111111-1111-1111-1111-111111111111/accept", sent.path)
            assertEquals("accept-key", sent.getHeader("Idempotency-Key"))
            val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("tok-1", body["actionToken"]!!.jsonPrimitive.content)
            assertEquals("0.005", body["modification"]!!.jsonObject["quantity"]!!.jsonPrimitive.content)
            assertEquals(2, h.server.requestCount)
            assertTrue(p.state.value.action is ActionState.Done)
            assertEquals(
                "MODIFIED",
                p.state.value.detail!!
                    .recommendation.status,
            )
        }

    @Test
    fun `only risk-reducing modifications are allowed`() =
        runTest(dispatcher) {
            val p = RecommendationPresenter(scope, h.repo, "11111111-1111-1111-1111-111111111111")
            h.server.enqueue(json(detailJson()))
            p.load()
            advanceUntilIdle()
            p.setQuantity("0.02")
            assertNotNull(p.state.value.validation)
            p.setQuantity("0")
            assertNotNull(p.state.value.validation)
            p.setQuantity("0.01")
            p.setLimitPrice("60001") // a higher buy limit is not permitted
            assertNotNull(p.state.value.validation)
            p.setLimitPrice("59990")
            assertNull(p.state.value.validation)
            p.accept()
            advanceUntilIdle()
            h.server.takeRequest()
            val body =
                Json
                    .parseToJsonElement(
                        h.server
                            .takeRequest()
                            .body
                            .readUtf8(),
                    ).jsonObject
            assertEquals("59990", body["modification"]!!.jsonObject["limitPrice"]!!.jsonPrimitive.content)
        }

    @Test
    fun `expired, deviated and already-decided recommendations explain that no order was placed`() =
        runTest(dispatcher) {
            val p = RecommendationPresenter(scope, h.repo, "11111111-1111-1111-1111-111111111111")
            h.server.enqueue(json(detailJson()))
            p.load()
            advanceUntilIdle()
            h.server.enqueue(problem("price-deviation", 409, "moved 2%"))
            h.server.enqueue(json(detailJson(status = "FAILED", token = null)))
            p.accept()
            advanceUntilIdle()
            val failed = p.state.value.action as ActionState.Failed
            assertTrue(failed.message.contains("No order was placed"))
            assertEquals(
                "FAILED",
                p.state.value.detail!!
                    .recommendation.status,
            )
            // A non-pending recommendation is not sent to the server again.
            val before = h.server.requestCount
            p.accept()
            advanceUntilIdle()
            assertEquals(before, h.server.requestCount)
        }

    @Test
    fun `close all requires the typed confirmation and retries after re-authentication`() =
        runTest(dispatcher) {
            val e = EmergencyPresenter(scope, h.repo)
            e.closeAll()
            assertTrue((e.state.value.action as ActionState.Failed).message.contains(EmergencyPresenter.CONFIRMATION))
            assertEquals(0, h.server.requestCount)
            e.setConfirmation(EmergencyPresenter.CONFIRMATION)
            h.server.enqueue(problem("recent-authentication-required", 403))
            e.closeAll()
            advanceUntilIdle()
            val failed = e.state.value.action as ActionState.Failed
            assertTrue(failed.needsReauth)
            assertNotNull(e.state.value.pendingReauth)
            val state = """{"pauseAll":false,"preventNewPositions":false,"updatedAt":"2026-06-22T13:00:00Z"}"""
            h.server.enqueue(json("""{"action":"CLOSE_ALL_SIMULATED_POSITIONS","state":$state,"affected":["a"],"detail":"1 closing order(s) submitted"}"""))
            h.server.enqueue(json(state))
            e.reauthenticated()
            advanceUntilIdle()
            assertEquals(ActionState.Done("1 closing order(s) submitted"), e.state.value.action)
            h.server.takeRequest()
            val retried = h.server.takeRequest()
            assertEquals("/v1/emergency/close-all-simulated-positions", retried.path)
        }

    @Test
    fun `first run bootstraps the owner and shows recovery codes once`() =
        runTest(dispatcher) {
            var saved: String? = null
            val a = AccessPresenter(scope, h.repo, { saved = it }, { if (it.startsWith("http")) it else null }, initialServer = null, signedIn = false)
            assertEquals(AccessStep.SERVER, a.state.value.step)
            a.connect("not-a-url")
            assertTrue(a.state.value.action is ActionState.Failed)
            h.server.enqueue(json("""{"bootstrapped":false,"bootstrapTokenRequired":false}"""))
            a.connect(h.server.url("/").toString())
            advanceUntilIdle()
            assertNotNull(saved)
            assertEquals(AccessStep.BOOTSTRAP, a.state.value.step)
            a.bootstrap("owner", "short", "short", "Pixel", "America/Halifax", null)
            assertTrue(a.state.value.action is ActionState.Failed)
            h.tokens.value = null
            h.server.enqueue(
                json("""{"session":{"sessionId":"s","accessToken":"new-token","expiresAt":"x","lastAuthenticatedAt":"y"},"recoveryCodes":["a-b","c-d"],"notice":"Store these"}""", 201),
            )
            a.bootstrap("owner", "correct-horse-battery", "correct-horse-battery", "Pixel", "America/Halifax", null)
            advanceUntilIdle()
            assertEquals(AccessStep.RECOVERY_CODES, a.state.value.step)
            assertEquals(listOf("a-b", "c-d"), a.state.value.recoveryCodes)
            assertEquals("new-token", h.tokens.value)
            a.recoveryCodesSaved()
            assertEquals(AccessStep.SIGNED_IN, a.state.value.step)
            assertTrue(
                a.state.value.recoveryCodes
                    .isEmpty(),
            )
        }
}
