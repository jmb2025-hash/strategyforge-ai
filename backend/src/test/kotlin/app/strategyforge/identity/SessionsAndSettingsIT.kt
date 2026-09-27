package app.strategyforge.identity

import app.strategyforge.support.IntegrationTest
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionsAndSettingsIT : IntegrationTest() {
    @Test
    fun `NFR-004 unauthenticated requests are rejected with problem details`() {
        val r = TestHttp(baseUrl).get("/v1/settings")
        assertThat(r.status).isEqualTo(401)
        assertThat(r.header("Content-Type")).contains("application/problem+json")
        assertThat(r.json["code"].asText()).isEqualTo("unauthenticated")
        val bad = TestHttp(baseUrl, token = "not-a-real-token-but-long-enough-000").get("/v1/settings")
        assertThat(bad.status).isEqualTo(401)
    }

    @Test
    fun `FR-002 sessions are listed and another device can be revoked`() {
        val a = TestOwner.client(baseUrl)
        val b = TestOwner.secondDevice(baseUrl)
        val list = a.get("/v1/sessions")
        assertThat(list.status).isEqualTo(200)
        val other = b.get("/v1/auth/me").json["sessionId"].asText()
        assertThat(list.json.map { it["id"].asText() }).contains(other)
        assertThat(a.delete("/v1/sessions/$other").status).isEqualTo(204)
        assertThat(b.get("/v1/auth/me").status).isEqualTo(401)
        assertThat(a.get("/v1/auth/me").status).isEqualTo(200)
    }

    @Test
    fun `FR-003 NFR-001 preferences persist with timezone, currency, theme and notification settings using ETags`() {
        val h = TestOwner.client(baseUrl)
        val g = h.get("/v1/settings")
        assertThat(g.status).isEqualTo(200)
        val etag = g.header("ETag")!!
        assertThat(g.json["timezone"].asText()).isEqualTo("America/Halifax")
        assertThat(g.json["notifications"]["lockScreenRedaction"].asBoolean()).isTrue()
        assertThat(g.json["portfolioDefaults"]["startingBalance"].asText()).isEqualTo("100000")
        val body = TestHttp.mapper.convertValue(g.json, MutableMap::class.java) as MutableMap<String, Any?>
        body.remove("updatedAt")
        body.remove("version")
        body.remove("riskDefaults")
        body["timezone"] = "America/Toronto"
        body["displayCurrency"] = "CAD"
        body["theme"] = "DARK"
        assertThat(h.put("/v1/settings", body).status).isEqualTo(428)
        val ok = h.put("/v1/settings", body, mapOf("If-Match" to etag))
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok.json["timezone"].asText()).isEqualTo("America/Toronto")
        assertThat(ok.json["displayCurrency"].asText()).isEqualTo("CAD")
        val stale = h.put("/v1/settings", body, mapOf("If-Match" to etag))
        assertThat(stale.status).isEqualTo(412)
        body["timezone"] = "America/Halifax"
        body["displayCurrency"] = "USD"
        assertThat(h.put("/v1/settings", body, mapOf("If-Match" to ok.header("ETag")!!)).status).isEqualTo(200)
    }

    @Test
    fun `FR-003 critical safety notifications cannot be disabled and unknown fields are rejected`() {
        val h = TestOwner.client(baseUrl)
        val g = h.get("/v1/settings")
        val body = TestHttp.mapper.convertValue(g.json, MutableMap::class.java) as MutableMap<String, Any?>
        body.remove("updatedAt")
        body.remove("version")
        body.remove("riskDefaults")
        @Suppress("UNCHECKED_CAST")
        val notif = (body["notifications"] as MutableMap<String, Any?>)
        @Suppress("UNCHECKED_CAST")
        (notif["categories"] as MutableMap<String, Any?>)["RISK_EVENT"] = false
        val r = h.put("/v1/settings", body, mapOf("If-Match" to g.header("ETag")!!))
        assertThat(r.status).`as`(r.body).isEqualTo(422)
        assertThat(r.json["code"].asText()).isEqualTo("critical-notification-required")
        @Suppress("UNCHECKED_CAST")
        (notif["categories"] as MutableMap<String, Any?>)["RISK_EVENT"] = true
        body["realMoneyTradingEnabled"] = true
        val unknown = h.put("/v1/settings", body, mapOf("If-Match" to g.header("ETag")!!))
        assertThat(unknown.status).`as`(unknown.body).isEqualTo(400)
        assertThat(unknown.json["code"].asText()).isEqualTo("malformed-request")
    }

    @Test
    fun `FR-004 diagnostics report backend, database, clock drift, AI and push status`() {
        val h = TestOwner.client(baseUrl)
        val r = h.get("/v1/diagnostics")
        assertThat(r.status).isEqualTo(200)
        val comps = r.json["components"].associateBy { it["component"].asText() }
        assertThat(comps.keys).contains("backend", "database", "clock", "ai-providers", "push-fcm")
        assertThat(comps["database"]!!["status"].asText()).isEqualTo("OK")
        assertThat(comps["clock"]!!["data"]["driftMs"].isNumber).isTrue()
        assertThat(comps["push-fcm"]!!["detail"].asText()).contains("inbox remains authoritative")
    }
}
