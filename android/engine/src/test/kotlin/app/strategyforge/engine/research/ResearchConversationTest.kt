package app.strategyforge.engine.research

import app.strategyforge.engine.Engine
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.db.str
import app.strategyforge.engine.support.Strategies
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * D-034 research as a conversation: a first message, follow-ups that see the earlier turns, Gemini
 * web search with sources, and compilation from the whole conversation into a validated strategy.
 */
class ResearchConversationTest {
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

    private fun sent() = mapper.readTree(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    private fun userText(body: com.fasterxml.jackson.databind.JsonNode) = body["contents"][0]["parts"][0]["text"].asText()

    @Test
    fun `a conversation starts from one message, follow-ups carry the earlier turns, and it compiles into a strategy`() {
        gemini()
        server.enqueue(json(Fixtures.geminiGrounded("## Findings\nThey trade Bitcoin and Ethereum. Entries follow retests of support.\n\nOpen questions: which timeframe?")))
        var d = e.research.startConversation(ConversationStart("Research the crypto trading strategies used by the group Chart Champions", "CRYPTO"))

        val first = sent()
        assertThat(first["systemInstruction"]["parts"][0]["text"].asText()).contains("research assistant in StrategyForge AI").contains("simulated money only")
        assertThat(first["tools"][0].has("google_search")).`as`("Gemini web search is on by default").isTrue()
        assertThat(userText(first))
            .contains("<owner_message>")
            .contains("Chart Champions")
            .contains("BTC-USD")
            .contains("ETH-USD")
            .doesNotContain("<conversation>")

        assertThat(d.session.title).isEqualTo("Research the crypto trading strategies used by the group Chart Champions")
        assertThat(d.session.assetClass).isEqualTo("CRYPTO")
        assertThat(d.session.timeframe).`as`("no timeframe asked up front").isEmpty()
        val turn1 = d.runs.single()
        assertThat(turn1.status).isEqualTo("SUCCEEDED")
        assertThat(turn1.ownerMessage).contains("Chart Champions")
        assertThat(turn1.sources.map { it.title }).containsExactly("chartchampions.com", "example.org")
        assertThat(turn1.promptVersion).isEqualTo(ResearchPrompts.CONVERSATION_VERSION)

        // A follow-up: the model sees the first exchange.
        server.enqueue(json(Fixtures.gemini("Use the 4h chart: buy BTC when the close crosses above EMA 20, stop 5%.", "STOP")))
        d = e.research.message(d.session.id, "Focus on Bitcoin on the 4 hour chart")
        val second = userText(sent())
        assertThat(second).contains("<conversation>").contains("Research the crypto trading strategies").contains("retests of support")
        assertThat(second.substringAfter("<owner_message>")).contains("Focus on Bitcoin on the 4 hour chart")
        assertThat(d.runs.map { it.ownerMessage }).containsExactly("Research the crypto trading strategies used by the group Chart Champions", "Focus on Bitcoin on the 4 hour chart")
        assertThat(d.runs[1].sources).`as`("a turn without web results is recorded, not failed").isEmpty()
        assertThat(d.runs[1].status).isEqualTo("SUCCEEDED")

        // Compile needs the owner's review; the compile prompt carries the whole conversation.
        assertThat(assertThrows<EngineException> { e.research.compile(d.session.id) }.code).isEqualTo("research-not-reviewed")
        e.research.review(d.session.id, ReviewRequest("APPROVE"))
        val strategy = JacksonCanonical.mapper.writeValueAsString(Strategies.alwaysLong("Chart Champions BTC", "4h"))
        server.enqueue(json(Fixtures.gemini(strategy, "STOP")))
        d = e.research.compile(d.session.id)
        val compile = sent()
        assertThat(compile["systemInstruction"]["parts"][0]["text"].asText()).contains("research conversation").contains("Choose metadata.timeframe")
        assertThat(compile.has("tools")).`as`("compilation never searches").isFalse()
        assertThat(userText(compile))
            .contains("Owner:\nResearch the crypto trading strategies")
            .contains("Owner:\nFocus on Bitcoin")
            .contains("tradable symbols: ")
            .contains("BTC-USD")
        val c = d.compilations.single()
        assertThat(c.status).`as`(c.issues.toString()).isEqualTo("COMPILED")
        assertThat(e.strategies.get(c.strategyId!!).name).isEqualTo("Chart Champions BTC")
    }

    @Test
    fun `the first usable provider is chosen and a conversation needs one`() {
        assertThat(assertThrows<EngineException> { e.research.startConversation(ConversationStart("Research something")) }.code).isEqualTo("no-ai-provider")
        assertThat(assertThrows<EngineException> { e.research.startConversation(ConversationStart("  ")) }.code).isEqualTo("invalid-message")
        gemini()
        server.enqueue(json(Fixtures.gemini("ok", "STOP")))
        val d = e.research.startConversation(ConversationStart("Research a stock investor", "US_EQUITY"))
        assertThat(d.session.providerType).isEqualTo("GEMINI")
        assertThat(userText(sent())).contains("US stocks and ETFs").contains("AAPL")
    }

    @Test
    fun `a failed turn can be retried with the same message and the conversation keeps going`() {
        gemini()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429}}"""))
        var d = e.research.startConversation(ConversationStart("Research Warren Buffett", "US_EQUITY"))
        assertThat(d.runs.single().failureCode).isEqualTo("RATE_LIMITED")
        server.takeRequest()
        server.enqueue(json(Fixtures.gemini("Buffett buys quality companies.", "STOP")))
        d = e.research.run(d.session.id)
        assertThat(userText(sent()).substringAfter("<owner_message>")).contains("Research Warren Buffett")
        assertThat(d.runs.last().status).isEqualTo("SUCCEEDED")
        assertThat(d.runs.last().ownerMessage).isEqualTo("Research Warren Buffett")
    }

    @Test
    fun `D-034 existing databases are upgraded in place and new ones start at the latest schema`() {
        assertThat(e.db.schemaVersion()).isEqualTo(Db.SCHEMA_VERSION)
        // A phone that installed the first build: base schema only, no schema_version row.
        val old = JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:"))
        old.transaction { Db.schemaStatements().forEach { old.execute(it, emptyList()) } }
        val db = Db(old)
        assertThat(db.sql("pragma table_info(research_runs)").list { it.str("name") }).doesNotContain("owner_message")
        db.migrate()
        assertThat(db.sql("pragma table_info(research_runs)").list { it.str("name") }).contains("owner_message")
        assertThat(db.schemaVersion()).isEqualTo(Db.SCHEMA_VERSION)
        db.migrate() // idempotent
        assertThat(db.schemaVersion()).isEqualTo(Db.SCHEMA_VERSION)
    }
}
