package app.strategyforge.android.core

import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.api.ServerUrl
import app.strategyforge.android.core.model.EmergencyState
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** NFR-004/NFR-007 on the client side: authentication, idempotent retries, problem mapping. */
class ApiClientTest {
    private val dispatcher = StandardTestDispatcher()
    private val h = Harness(dispatcher)

    @After
    fun close() = h.close()

    @Test
    fun `mutations carry one idempotency key that is reused on network retries`() =
        runTest(dispatcher) {
            h.server.enqueue(json("").setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            h.server.enqueue(json("""{"action":"PAUSE_ALL","state":{"pauseAll":true,"preventNewPositions":false,"updatedAt":"2026-06-22T13:30:00Z"},"affected":[],"detail":"ok"}"""))
            val r = h.repo.pauseAll(true)
            assertTrue(r.state.pauseAll)
            val first = h.server.takeRequest()
            val second = h.server.takeRequest()
            assertEquals("key-1", first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
            assertEquals("Bearer test-token", second.getHeader("Authorization"))
        }

    @Test
    fun `problems are parsed and 401 clears the session`() =
        runTest(dispatcher) {
            h.server.enqueue(problem("recent-authentication-required", 403, "Operation needs recent authentication"))
            try {
                h.repo.closeAllSimulatedPositions("CLOSE ALL SIMULATED POSITIONS")
                fail("expected failure")
            } catch (e: ApiError.Http) {
                assertTrue(e.recentAuthRequired)
                assertEquals("Operation needs recent authentication", e.message)
            }
            h.server.enqueue(problem("unauthenticated", 401))
            try {
                h.repo.emergency()
                fail("expected failure")
            } catch (e: ApiError.Unauthorized) {
                assertNull(h.tokens.value)
            }
        }

    @Test
    fun `unreachable backend is an Offline error after retries`() =
        runTest(dispatcher) {
            repeat(3) { h.server.enqueue(json("").setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            try {
                h.api.get("/v1/emergency", EmergencyState.serializer())
                fail("expected failure")
            } catch (e: ApiError.Offline) {
                assertEquals(3, h.server.requestCount)
            }
        }

    @Test
    fun `server addresses must use HTTPS except local development hosts`() {
        assertEquals("https://sf.example.net", ServerUrl.validate(" https://sf.example.net/ ", false))
        assertNull(ServerUrl.validate("http://sf.example.net", false))
        assertNull(ServerUrl.validate("http://10.0.2.2:8080", false))
        assertEquals("http://10.0.2.2:8080", ServerUrl.validate("http://10.0.2.2:8080", true))
        assertNull(ServerUrl.validate("https://user:pw@sf.example.net", false))
        assertNull(ServerUrl.validate("not a url", true))
    }
}
