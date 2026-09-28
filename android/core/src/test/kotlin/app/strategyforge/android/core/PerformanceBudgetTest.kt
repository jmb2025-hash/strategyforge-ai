package app.strategyforge.android.core

import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.Dashboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * NFR-005 budgets measured in wall-clock time on the real dashboard path: with a slow backend,
 * the cached dashboard is shown in under 2 seconds and a normal network refresh completes in
 * under 5 seconds. Device rendering time is covered by the owner acceptance checklist.
 */
class PerformanceBudgetTest {
    private val h = Harness(Dispatchers.IO)

    @After
    fun close() = h.close()

    private fun slowBackend(delayMs: Long) {
        h.server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body =
                        when (request.path?.substringBefore('?')) {
                            "/v1/portfolios", "/v1/strategies" -> "[]"
                            "/v1/emergency" -> """{"pauseAll":false,"preventNewPositions":false,"updatedAt":"2026-06-22T13:00:00Z","version":1}"""
                            "/v1/notifications/unread-count" -> """{"unread":2}"""
                            "/v1/recommendations" -> """{"items":[]}"""
                            "/v1/diagnostics" -> """{"overall":"OK","generatedAt":"2026-06-22T13:00:00Z","components":[]}"""
                            else -> return problem("not-found", 404)
                        }
                    return json(body).setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
                }
            }
    }

    @Test
    fun `NFR-005 cached dashboard under 2 seconds and network refresh under 5 seconds`() =
        runBlocking {
            // A realistic but slow private-network backend: 300 ms per request.
            slowBackend(300)
            val coldStart = System.nanoTime()
            var cold: Resource<Dashboard>? = null
            h.repo.dashboard().collect { cold = it }
            val coldMs = (System.nanoTime() - coldStart) / 1_000_000
            assertTrue("network refresh took $coldMs ms", coldMs < 5_000)
            assertFalse((cold as Resource.Data).fromCache)

            // Second open: the cached copy must appear first, well before the refresh completes.
            val start = System.nanoTime()
            var firstMs = -1L
            var firstFromCache = false
            var last: Resource<Dashboard>? = null
            h.repo.dashboard().collect { r ->
                if (firstMs < 0) {
                    firstMs = (System.nanoTime() - start) / 1_000_000
                    firstFromCache = (r as? Resource.Data)?.fromCache == true
                }
                last = r
            }
            val refreshMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("cached dashboard shown after $firstMs ms", firstFromCache && firstMs < 2_000)
            assertTrue("refresh completed after $refreshMs ms", refreshMs < 5_000)
            assertFalse((last as Resource.Data).fromCache)
            assertTrue((last as Resource.Data).value.unread.unread == 2)
        }
}
