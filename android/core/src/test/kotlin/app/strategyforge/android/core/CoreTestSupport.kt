package app.strategyforge.android.core

import app.strategyforge.android.core.api.ApiClient
import app.strategyforge.android.core.api.TokenStore
import app.strategyforge.android.core.cache.CachedResource
import app.strategyforge.android.core.cache.MemoryCacheStore
import app.strategyforge.android.core.data.Repository
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Clock
import java.util.concurrent.TimeUnit

class MemoryTokens(
    var value: String? = "test-token",
) : TokenStore {
    override fun token() = value

    override fun save(token: String?) {
        value = token
    }
}

fun json(
    body: String,
    code: Int = 200,
): MockResponse = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

fun problem(
    code: String,
    status: Int,
    detail: String = "detail for $code",
) = json("""{"type":"https://strategyforge.app/problems/$code","title":"t","status":$status,"detail":"$detail","code":"$code"}""", status)

class Harness(
    io: CoroutineDispatcher,
    clock: Clock = Clock.systemUTC(),
) {
    val server = MockWebServer().also { it.start() }
    val tokens = MemoryTokens()
    val store = MemoryCacheStore()
    private var keys = 0
    val api =
        ApiClient(
            { server.url("/").toString() },
            OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
            tokens,
            newKey = { "key-${++keys}" },
            io = io,
            retryDelaysMs = listOf(1, 1),
        )
    val cache = CachedResource(store, clock)
    val repo = Repository(api, cache, tokens)

    fun close() = server.shutdown()
}

const val RECOMMENDATION_JSON =
    """{"id":"11111111-1111-1111-1111-111111111111","signalId":"s","strategyId":"22222222-2222-2222-2222-222222222222","strategyName":"Momentum",
    "portfolioId":"33333333-3333-3333-3333-333333333333","instrumentId":"i","symbol":"BTC-USD","status":"PENDING","side":"BUY","quantity":"0.010000000000000000",
    "orderType":"LIMIT","limitPrice":"60000.00","referencePrice":"60010.00","maxDeviationPercent":"1.0","expiresAt":"2026-06-22T14:01:00Z","rationale":"Entry: CLOSE GT 1",
    "triggeredRules":["CLOSE GT 1"],"versionHash":"abc","marketSnapshotId":"m","createdAt":"2026-06-22T13:31:00Z","version":0}"""

fun detailJson(
    status: String = "PENDING",
    token: String? = "tok-1",
) = """{"recommendation":${RECOMMENDATION_JSON.replace("\"PENDING\"", "\"$status\"")},
    "actionToken":${token?.let { """{"token":"$it","expiresAt":"2026-06-22T13:40:00Z"}""" } ?: "null"},"decisions":[],"disclaimer":"Simulated."}"""
