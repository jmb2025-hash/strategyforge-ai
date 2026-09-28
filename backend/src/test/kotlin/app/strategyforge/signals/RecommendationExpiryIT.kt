package app.strategyforge.signals

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.Strategies
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext

/** FR-064 and MS-10 expiry: acceptance is rejected after material price deviation or expiry. */
class RecommendationExpiryIT : FreshDatabaseTest() {
    @Test
    fun `FR-064 FR-066 MS-10 acceptance fails after price deviation and recommendations expire`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h, costModel = mapOf("maxPriceDeviationPercent" to "0.1"))
        val sid = Strategies.eligible(h, Strategies.alwaysLong("Always Long 4h", "4h"), "2026-03-01T00:00:00Z", "2026-06-20T00:00:00Z")
        assertThat(Strategies.activate(h, sid, p).status).isEqualTo(201)

        // 13:31: the 08:00 4h bar is the latest closed bucket; the recommendation expires after 30 minutes.
        Replay.advance(h, 1)
        val rec = Strategies.recommendations(h, sid, "PENDING").single()
        val id = rec["id"].asText()
        val ref = BigDecimal(rec["referencePrice"].asText())
        assertThat(rec["expiresAt"].asText()).isEqualTo("2026-06-22T14:01:00Z")

        // The synthetic replay series is too smooth to move 0.1% within the expiry window, so the
        // market move is simulated by shifting the recommendation's reference price by 1%.
        jdbc
            .sql("update recommendations set reference_price = :r where id = cast(:id as uuid)")
            .param("r", ref.multiply(BigDecimal("0.99"), MathContext.DECIMAL64))
            .param("id", id)
            .update()
        Replay.advance(h, 1)
        val token = h.get("/v1/recommendations/$id").json["actionToken"]["token"].asText()
        val acc = h.post("/v1/recommendations/$id/accept", mapOf("actionToken" to token))
        assertThat(acc.status).`as`(acc.toString()).isEqualTo(409)
        assertThat(acc.json["code"].asText()).isEqualTo("price-deviation")
        val failed = h.get("/v1/recommendations/$id").json["recommendation"]
        assertThat(failed["status"].asText()).isEqualTo("FAILED")
        assertThat(failed["orderId"].isNull).isTrue()

        // 16:00: the next 4h bucket produces a new recommendation, which then expires unanswered.
        h.post("/v1/market-data/replay/set", mapOf("to" to "2026-06-22T15:59:00Z"))
        Replay.advance(h, 1)
        val next = Strategies.recommendations(h, sid, "PENDING").single()
        assertThat(next["expiresAt"].asText()).isEqualTo("2026-06-22T16:30:00Z")
        Replay.advance(h, 31, 31)
        val expired = h.get("/v1/recommendations/${next["id"].asText()}").json
        assertThat(expired["recommendation"]["status"].asText()).isEqualTo("EXPIRED")
        assertThat(expired["actionToken"].isNull).isTrue()
        assertThat(expired["decisions"].map { it["decision"].asText() }).contains("CREATE", "EXPIRE")
        assertThat(jdbc.sql("select count(*) from paper_orders").query(Int::class.java).single()).isZero()
    }
}
