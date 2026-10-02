package app.strategyforge.engine.research

import app.strategyforge.engine.Engine
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.support.Strategies
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/** D-041 research done elsewhere: pasted in, digested in parts when long, and compiled into a strategy. */
class ResearchImportTest {
    private val server = MockWebServer().also { it.start() }
    private val e =
        Engine(
            JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
            fixtureReader = { rel -> javaClass.getResource("/replay/$rel")!!.readText() },
            secrets = InMemorySecretStore(),
            allowLocalProviderHttp = true,
        )
    private val mapper = ObjectMapper()

    @AfterEach
    fun stop() = server.shutdown()

    private fun gemini() =
        e.aiProviders
            .create(AiProviderType.GEMINI, "Gemini", AiProviderType.GEMINI.presets + ("baseUrl" to server.url("/").toString().trimEnd('/')), "AIza-test-not-real")
            .id

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun userText() = mapper.readTree(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())["contents"][0]["parts"][0]["text"].asText()

    private fun strategy(name: String) = JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong(name, "4h"))

    @Test
    fun `short pasted research is compiled straight into a strategy with a single AI request`() {
        gemini()
        server.enqueue(json(Fixtures.gemini(strategy("Pasted BTC trend"), "STOP")))
        val text = "## Chart Champions research\nThey trade BTC on the 4h chart. Enter when the close crosses above the 20 EMA; stop 5%, target 10%."
        val d = e.research.importResearch(ResearchImport(text, "CRYPTO"))

        assertThat(d.session.importChunk).isEqualTo(0)
        assertThat(d.session.title).isEqualTo("Imported: ## Chart Champions research")
        assertThat(d.runs.map { it.purpose }).containsExactly("RESEARCH", "COMPILE")
        assertThat(d.runs.first().model).`as`("the pasted text is recorded, not sent for research").isEqualTo("imported")
        assertThat(d.session.reviewStatus).isEqualTo("REVIEWED")
        assertThat(d.compilations.single().status).isEqualTo("COMPILED")
        assertThat(e.strategies.get(d.compilations.single().strategyId!!).name).isEqualTo("Pasted BTC trend")
        assertThat(userText()).contains("Enter when the close crosses above the 20 EMA")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `long research is digested part by part, resumes after a quota error, then compiles the digests`() {
        gemini()
        val b = e.aiBudget.get()
        e.aiBudget.update(AiBudgetUpdate(b.monthlyCostLimitUsd, b.dailyRequestLimit, b.maxOutputTokens, 30_000), b.version)
        val paragraph = "Entry: buy ETH when RSI(14) crosses above 30 on the 1h chart and volume is 1.5x its average. "
        val text = (1..800).joinToString("\n\n") { "Section $it. $paragraph" }
        assertThat(text.length).isGreaterThan(30_000)
        val parts = ResearchService.parts(text, 15_000)
        assertThat(parts.joinToString("")).isEqualTo(text)

        server.enqueue(json(Fixtures.gemini("Part 1 rules: RSI(14) crosses above 30, volume filter 1.5x.", "STOP")))
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":429,"message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 10. Please retry in 41s.","status":"RESOURCE_EXHAUSTED"}}"""),
        )
        var d = e.research.importResearch(ResearchImport(text, "CRYPTO"))
        val n = d.session.importChunk!!.let { ResearchService.parts(text, it).size }
        assertThat(n).isGreaterThanOrEqualTo(3)
        assertThat(userText()).contains("<part number=\"1\" of=\"$n\">").doesNotContain("Section 800.")
        assertThat(userText()).contains("<part number=\"2\"")
        val failed = d.runs.last()
        assertThat(failed.status).isEqualTo("FAILED")
        assertThat(failed.failureCode).isEqualTo("RATE_LIMITED")
        assertThat(failed.failureDetail).contains("Please retry in 41s")
        assertThat(d.compilations).isEmpty()

        // "Try again" resumes at the part that failed, then compiles once every part is digested.
        repeat(n - 1) { k -> server.enqueue(json(Fixtures.gemini("Part ${k + 2} rules: exit when RSI above 70; stop 4%.", "STOP"))) }
        server.enqueue(json(Fixtures.gemini(strategy("Imported ETH RSI"), "STOP")))
        d = e.research.run(d.session.id)
        repeat(n - 1) { k -> assertThat(userText()).contains("<part number=\"${k + 2}\" of=\"$n\">") }
        val compile = userText()
        assertThat(compile).contains("Part 1 rules").contains("Part $n rules").doesNotContain("Section 1.")
        assertThat(d.compilations.single().status).isEqualTo("COMPILED")
        assertThat(d.runs.count { it.purpose == "RESEARCH" && it.status == "SUCCEEDED" }).isEqualTo(n)
    }

    @Test
    fun `parts split at paragraph breaks and keep every character`() {
        val text = "a".repeat(700) + "\n\n" + "b".repeat(700) + "\n\n" + "c".repeat(300)
        val parts = ResearchService.parts(text, 1000)
        assertThat(parts).hasSize(3)
        assertThat(parts[0]).`as`("cut at the paragraph break, not mid-word").isEqualTo("a".repeat(700) + "\n\n")
        assertThat(parts[1]).startsWith("b")
        assertThat(parts.joinToString("")).isEqualTo(text)
        assertThat(ResearchService.parts("short", 1000)).containsExactly("short")
        assertThat(ResearchService.parts("x".repeat(2500), 1000).map { it.length }).containsExactly(1000, 1000, 500)
    }

    @Test
    fun `instructions for an outside AI carry the schema, the tradable symbols and the imported marker`() {
        val prompt = e.research.authoringPrompt("CRYPTO")
        assertThat(prompt)
            .contains("\"IMPORTED\"")
            .contains("BTC-USD")
            .contains("\"schemaVersion\"")
            .contains("BULLISH_ENGULFING")
            .contains("research that specific point")
            .contains("trading plan")
            .contains("\"schemaVersion\": \"2.0\"")
            .contains("\"setups\"")
            .contains("conflictPolicy")
            .contains("RULE READBACK")
            .contains("FURTHER RESEARCH")
            .contains("STILL MISSING")
            .contains("do not ask me")
            .endsWith("My research (if it is not already above):\n\n")
        assertThat(e.research.authoringPrompt("US_EQUITY")).contains("AAPL").contains("\"US_EQUITY\"")
    }
}
