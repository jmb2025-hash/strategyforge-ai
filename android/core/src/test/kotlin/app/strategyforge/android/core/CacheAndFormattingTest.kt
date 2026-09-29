package app.strategyforge.android.core

import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.format.PnlDirection
import app.strategyforge.android.core.notify.Channel
import app.strategyforge.android.core.notify.DeepLinks
import app.strategyforge.android.core.notify.NotificationPresenter
import app.strategyforge.android.core.notify.PushPayload
import app.strategyforge.android.core.notify.Route
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** NFR-003 cache-only Room semantics, NFR-001 timezone display, NFR-009 non-colour cues, FR-102 redaction. */
class CacheAndFormattingTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-06-22T14:00:00Z"), ZoneOffset.UTC)
    private val h = Harness(dispatcher, clock)
    private val emergency = """{"pauseAll":false,"preventNewPositions":true,"updatedAt":"2026-06-22T13:00:00Z","version":3}"""

    @After
    fun close() = h.close()

    @Test
    fun `NFR-003 NFR-005 cache is shown first, then replaced by the authoritative network copy`() =
        runTest(dispatcher) {
            h.server.enqueue(json(emergency))
            val first =
                h.cache
                    .load(
                        "e",
                        app.strategyforge.android.core.model.EmergencyState
                            .serializer(),
                    ) { h.api.get("/v1/emergency").body }
                    .toList()
            assertEquals(Resource.Loading, first[0])
            val fresh = first[1] as Resource.Data
            assertFalse(fresh.fromCache)
            assertTrue(fresh.value.preventNewPositions)

            h.server.enqueue(json(emergency.replace("\"preventNewPositions\":true", "\"preventNewPositions\":false")))
            val second =
                h.cache
                    .load(
                        "e",
                        app.strategyforge.android.core.model.EmergencyState
                            .serializer(),
                    ) { h.api.get("/v1/emergency").body }
                    .toList()
            val cached = second[0] as Resource.Data
            assertTrue(cached.fromCache)
            assertTrue(cached.refreshing)
            assertTrue(cached.value.preventNewPositions)
            assertFalse((second[1] as Resource.Data).value.preventNewPositions)
        }

    @Test
    fun `offline keeps cached data visible and labelled, without cache the failure is shown`() =
        runTest(dispatcher) {
            h.server.enqueue(json(emergency))
            h.cache
                .load(
                    "e",
                    app.strategyforge.android.core.model.EmergencyState
                        .serializer(),
                ) { h.api.get("/v1/emergency").body }
                .toList()
            h.server.shutdown() // the backend becomes unreachable
            val offline =
                h.cache
                    .load(
                        "e",
                        app.strategyforge.android.core.model.EmergencyState
                            .serializer(),
                    ) { h.api.get("/v1/emergency").body }
                    .toList()
                    .last() as Resource.Data
            assertTrue("offline $offline", offline.offline)
            assertTrue(offline.toString(), offline.stale)
            assertTrue("fromCache $offline", offline.fromCache)
            val none =
                h.cache
                    .load(
                        "other",
                        app.strategyforge.android.core.model.EmergencyState
                            .serializer(),
                    ) { h.api.get("/v1/emergency").body }
                    .toList()
                    .last()
            assertTrue("none $none", none is Resource.Failure)
        }

    @Test
    fun `NFR-001 NFR-009 formatting uses decimals, timezone and non-colour P and L cues`() {
        val f = Formatters(ZoneId.of("America/Halifax"))
        assertEquals("$1,234.57", f.money("1234.566"))
        assertEquals("-$0.10", f.money("-0.1"))
        assertEquals("CA$1,370.00", f.money("1000", "CAD", BigDecimal("1.37")))
        assertEquals("— CAD (no FX rate)", f.money("1000", "CAD", null))
        assertEquals("0.01", f.quantity("0.010000000000000000"))
        val gain = f.pnl("12.3")
        assertEquals(PnlDirection.GAIN, gain.direction)
        assertEquals("▲ +$12.30", gain.text)
        assertEquals("gain of $12.30", gain.contentDescription)
        assertEquals("▼ -$4.00", f.pnl("-4").text)
        assertEquals("■ $0.00", f.pnl("0").text)
        assertTrue(f.dateTime("2026-06-22T13:30:00Z").contains("10:30") && f.dateTime("2026-06-22T13:30:00Z").endsWith("America/Halifax"))
        assertEquals("5 min ago", f.age("2026-06-22T13:55:00Z", Instant.parse("2026-06-22T14:00:00Z")))
        assertEquals("—", f.money(null))
    }

    @Test
    fun `FR-102 lock-screen text is always generic and unlocked text can be redacted too`() {
        val p = PushPayload("n1", "recommendation_action", "Recommendation: BUY 0.01 BTC-USD", "Momentum: entry", "strategyforge://recommendation/11111111-1111-1111-1111-111111111111")
        val shown = NotificationPresenter.present(p, redactUnlocked = false)
        assertEquals(Channel.RECOMMENDATION, shown.channel)
        assertFalse(shown.publicTitle.contains("BTC"))
        assertFalse(shown.publicBody.contains("BTC"))
        assertEquals("Recommendation: BUY 0.01 BTC-USD", shown.privateTitle)
        assertEquals(Route.Recommendation("11111111-1111-1111-1111-111111111111"), shown.route)
        val hidden = NotificationPresenter.present(p, redactUnlocked = true)
        assertFalse(hidden.privateTitle.contains("BTC"))
        assertEquals(Channel.RISK, NotificationPresenter.present(p.copy(channel = "unknown"), false).channel)
    }

    @Test
    fun `deep links accept only known shapes with UUID identifiers`() {
        val id = "11111111-1111-1111-1111-111111111111"
        assertEquals(Route.Order(id), DeepLinks.parse("strategyforge://paperorder/$id"))
        assertEquals(Route.Strategy(id), DeepLinks.parse("/app/strategies/$id?x=1"))
        assertEquals(Route.Emergency, DeepLinks.parse("strategyforge://emergency/"))
        assertEquals(Route.Inbox, DeepLinks.parse("strategyforge://inbox"))
        assertNull(DeepLinks.parse("strategyforge://recommendation/../../accept"))
        assertNull(DeepLinks.parse("https://evil.example/recommendation/$id"))
        assertNull(DeepLinks.parse("strategyforge://recommendation/$id/accept"))
        assertNull(DeepLinks.parse("strategyforge://unknown/$id"))
        assertEquals(Route.Research(id), DeepLinks.parse(DeepLinks.toUri(Route.Research(id))))
    }

    @Test
    fun `D-038 chart axis labels follow the span and show prices compactly`() {
        val f = Formatters(ZoneId.of("UTC"))
        val t = Instant.parse("2026-10-01T14:05:00Z").toEpochMilli()
        assertEquals("14:05", f.chartTime(t, 3_600_000))
        assertEquals("Oct 1", f.chartTime(t, 7L * 86_400_000))
        assertEquals("Oct 2026", f.chartTime(t, 365L * 86_400_000))
        assertEquals("64,512", f.chartNumber(64_512.34))
        assertEquals("187.25", f.chartNumber(187.25))
        assertEquals("1.2346", f.chartNumber(1.23456))
        assertEquals("0.004512", f.chartNumber(0.004512))
        assertEquals("2.5M", f.chartNumber(2_500_000.0))
        assertEquals(t, f.epochMillis("2026-10-01T14:05:00Z"))
        assertNull(f.epochMillis("not a time"))
    }
}
