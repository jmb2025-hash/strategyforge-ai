package app.strategyforge.engine.research

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.TimeUnit

/** FR-030 common adapter and MS-18 failure handling, against recorded provider responses (ported from the server tests). */
class AiClientsTest {
    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()
    private val http = OkHttpClient()

    @BeforeEach
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun provider(
        type: AiProviderType,
        extra: Map<String, String> = emptyMap(),
    ) = AiProviderConfig(
        UUID.randomUUID(),
        type,
        "test",
        mapOf("model" to "test-model", "baseUrl" to server.url("/").toString().trimEnd('/'), "timeoutSeconds" to "2", "inputPricePerMillionTokensUsd" to "5", "outputPricePerMillionTokensUsd" to "25") + extra,
        "test-credential-not-real",
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val request = AiRequest("system text", "user text", 500, false)

    @Test
    fun `FR-030 FR-033 Anthropic adapter returns text, usage, search count and cited sources`() {
        server.enqueue(json(Fixtures.anthropicWithCitations))
        val p = provider(AiProviderType.ANTHROPIC, mapOf("webSearchEnabled" to "true"))
        val r = AnthropicAiClient().complete(p, request.copy(retrieval = true, maxSearches = 3))
        assertThat(r.text).contains("Momentum").contains("persists")
        assertThat(r.sources.map { it.url }).containsExactly("https://example.org/momentum-study")
        assertThat(r.sources.single().citedText).contains("twelve-month")
        assertThat(r.inputTokens).isEqualTo(1200)
        assertThat(r.outputTokens).isEqualTo(800)
        assertThat(r.searchRequests).isEqualTo(1)
        val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(sent.path).startsWith("/v1/messages")
        assertThat(sent.getHeader("x-api-key")).isEqualTo("test-credential-not-real")
        val body = mapper.readTree(sent.body.readUtf8())
        assertThat(body["tools"][0]["type"].asText()).isEqualTo("web_search_20260209")
        assertThat(body["tools"][0]["max_uses"].asInt()).isEqualTo(3)
        assertThat(body["system"].asText()).isEqualTo("system text")
        assertThat(body["max_tokens"].asInt()).isEqualTo(500)
    }

    @Test
    fun `Anthropic refusal, truncation and paused turns are failures, not partial answers`() {
        val p = provider(AiProviderType.ANTHROPIC)
        mapOf("refusal" to AiFailure.REFUSED, "max_tokens" to AiFailure.TRUNCATED, "pause_turn" to AiFailure.INCOMPLETE).forEach { (stop, failure) ->
            server.enqueue(json(Fixtures.anthropicText("partial", stop)))
            assertThatThrownBy { AnthropicAiClient().complete(p, request) }.isInstanceOfSatisfying(AiException::class.java) { assertThat(it.failure).isEqualTo(failure) }
        }
    }

    @Test
    fun `Anthropic server-side refusal fallback is requested for supported models`() {
        server.enqueue(json(Fixtures.anthropicText("ok", "end_turn")))
        AnthropicAiClient().complete(provider(AiProviderType.ANTHROPIC, mapOf("model" to "claude-opus-5")), request)
        val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(sent.getHeader("anthropic-beta")).contains(AnthropicAiClient.FALLBACK_BETA)
        assertThat(mapper.readTree(sent.body.readUtf8())["fallbacks"].asText()).isEqualTo("default")
    }

    @Test
    fun `MS-18 HTTP failures are classified for every adapter`() {
        val cases = listOf(401 to AiFailure.AUTHENTICATION, 429 to AiFailure.RATE_LIMITED, 400 to AiFailure.BAD_REQUEST, 500 to AiFailure.PROVIDER_ERROR)
        AiProviderType.entries.forEach { type ->
            cases.forEach { (status, failure) ->
                // The Anthropic SDK retries retryable statuses once; enqueue enough responses.
                repeat(2) { server.enqueue(json("""{"type":"error","error":{"type":"x","message":"simulated"}}""").setResponseCode(status)) }
                assertThatThrownBy { client(type).complete(provider(type), request) }
                    .`as`("$type $status")
                    .isInstanceOfSatisfying(AiException::class.java) { assertThat(it.failure).isEqualTo(failure) }
                while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) Unit
                server.shutdown()
                server = MockWebServer().also { it.start() }
            }
        }
    }

    @Test
    fun `MS-18 timeouts and malformed JSON`() {
        listOf(AiProviderType.OPENAI, AiProviderType.GEMINI).forEach { type ->
            server.enqueue(json(Fixtures.openAi("late")).setBodyDelay(4, TimeUnit.SECONDS))
            assertThatThrownBy { client(type).complete(provider(type, mapOf("timeoutSeconds" to "1")), request) }
                .isInstanceOfSatisfying(AiException::class.java) { assertThat(it.failure).isEqualTo(AiFailure.TIMEOUT) }
            server.shutdown()
            server = MockWebServer().also { it.start() }
            server.enqueue(json("{not json"))
            assertThatThrownBy { client(type).complete(provider(type), request) }
                .isInstanceOfSatisfying(AiException::class.java) { assertThat(it.failure).isEqualTo(AiFailure.MALFORMED_RESPONSE) }
            server.enqueue(json("""{"unexpected": true}"""))
            assertThatThrownBy { client(type).complete(provider(type), request) }
                .isInstanceOfSatisfying(AiException::class.java) { assertThat(it.failure).isEqualTo(AiFailure.MALFORMED_RESPONSE) }
        }
    }

    @Test
    fun `OpenAI and OpenRouter use chat completions with the right token parameter and reported cost`() {
        server.enqueue(json(Fixtures.openAi("Memo text")))
        val r = OpenAiCompatibleClient(http).complete(provider(AiProviderType.OPENAI), request)
        assertThat(r.text).isEqualTo("Memo text")
        assertThat(r.inputTokens).isEqualTo(100)
        assertThat(r.reportedCostUsd).isNull()
        val openAi = mapper.readTree(server.takeRequest().body.readUtf8())
        assertThat(openAi["max_completion_tokens"].asInt()).isEqualTo(500)
        assertThat(openAi["messages"][0]["role"].asText()).isEqualTo("system")

        server.enqueue(json(Fixtures.openAi("Router memo", cost = "0.0123")))
        val routed = OpenAiCompatibleClient(http).complete(provider(AiProviderType.OPENROUTER), request)
        assertThat(routed.reportedCostUsd).isEqualByComparingTo(BigDecimal("0.0123"))
        val sent = server.takeRequest()
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer test-credential-not-real")
        assertThat(mapper.readTree(sent.body.readUtf8())["max_tokens"].asInt()).isEqualTo(500)

        server.enqueue(json(Fixtures.openAi("cut", finish = "length")))
        assertThatThrownBy { OpenAiCompatibleClient(http).complete(provider(AiProviderType.OPENAI), request) }
            .isInstanceOfSatisfying(AiException::class.java) { assertThat(it.failure).isEqualTo(AiFailure.TRUNCATED) }
    }

    @Test
    fun `Gemini generateContent parses text and usage and flags safety blocks`() {
        server.enqueue(json(Fixtures.gemini("Gemini memo", "STOP")))
        val r = GeminiClient(http).complete(provider(AiProviderType.GEMINI), request)
        assertThat(r.text).isEqualTo("Gemini memo")
        assertThat(r.outputTokens).isEqualTo(70) // 50 answer + 20 thinking tokens
        val sent = server.takeRequest()
        assertThat(sent.path).isEqualTo("/v1beta/models/test-model:generateContent")
        assertThat(sent.getHeader("x-goog-api-key")).isEqualTo("test-credential-not-real")
        server.enqueue(json(Fixtures.gemini("", "SAFETY")))
        assertThatThrownBy { GeminiClient(http).complete(provider(AiProviderType.GEMINI), request) }
            .isInstanceOfSatisfying(AiException::class.java) { assertThat(it.failure).isEqualTo(AiFailure.REFUSED) }
    }

    @Test
    fun `D-034 Gemini web search attaches Google Search grounding and returns the cited sources`() {
        server.enqueue(json(Fixtures.geminiGrounded("They trade Bitcoin and Ethereum. Entries follow retests of support.")))
        val p = provider(AiProviderType.GEMINI, mapOf("model" to "gemini-2.5-flash", "inputPricePerMillionTokensUsd" to "0", "outputPricePerMillionTokensUsd" to "0"))
        assertThat(AiClients.default(http).retrievalAvailable(p)).`as`("web search is on for Gemini unless turned off").isTrue()
        assertThat(p.webSearchPrice()).`as`("free-tier Gemini searches are free").isEqualByComparingTo("0")
        assertThat(AiClients.default(http).retrievalAvailable(p.copy(settings = p.settings + ("webSearchEnabled" to "false")))).isFalse()

        val r = GeminiClient(http).complete(p, request.copy(retrieval = true, maxSearches = 5, maxOutputTokens = 8000))
        assertThat(r.sources.map { it.url }).containsExactly("https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc", "https://example.org/crypto-trading-review")
        assertThat(r.sources.first().title).isEqualTo("chartchampions.com")
        assertThat(r.sources.first().citedText).contains("Bitcoin").contains("retests of support")
        assertThat(r.searchRequests).isEqualTo(1)
        val body = mapper.readTree(server.takeRequest().body.readUtf8())
        assertThat(body["tools"][0].has("google_search")).isTrue()
        assertThat(body["generationConfig"]["thinkingConfig"]["thinkingBudget"].asInt()).isEqualTo(2000)

        // Without retrieval no tool is attached and no sources are claimed.
        server.enqueue(json(Fixtures.geminiGrounded("answer")))
        assertThat(GeminiClient(http).complete(p, request).sources).isEmpty()
        assertThat(mapper.readTree(server.takeRequest().body.readUtf8()).has("tools")).isFalse()
    }

    @Test
    fun `adapters without retrieval refuse retrieval requests`() {
        assertThatThrownBy { OpenAiCompatibleClient(http).complete(provider(AiProviderType.OPENAI), request.copy(retrieval = true)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(AiClients(listOf(AnthropicAiClient())).retrievalAvailable(provider(AiProviderType.OPENAI, mapOf("webSearchEnabled" to "true")))).isFalse()
    }

    private fun client(type: AiProviderType): AiClient =
        when (type) {
            AiProviderType.ANTHROPIC -> AnthropicAiClient()
            AiProviderType.GEMINI -> GeminiClient(http)
            else -> OpenAiCompatibleClient(http)
        }
}

/** Recorded provider response shapes (the same shapes as the Version 1 server tests). */
object Fixtures {
    private val m = ObjectMapper()

    val anthropicWithCitations =
        """
        {"id":"msg_test_1","type":"message","role":"assistant","model":"claude-opus-5","stop_reason":"end_turn","stop_sequence":null,
         "content":[
          {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"time series momentum equities"}},
          {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
            {"type":"web_search_result","url":"https://example.org/momentum-study","title":"Momentum study","encrypted_content":"ZW5j","page_age":"2025-01-01"}]},
          {"type":"text","text":"Thesis: Momentum ","citations":null},
          {"type":"text","text":"persists over twelve months.","citations":[
            {"type":"web_search_result_location","url":"https://example.org/momentum-study","title":"Momentum study","encrypted_index":"aWR4","cited_text":"twelve-month returns predict future returns"}]}
         ],
         "usage":{"input_tokens":1200,"output_tokens":800,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"server_tool_use":{"web_search_requests":1}}}
        """.trimIndent()

    fun anthropicText(
        text: String,
        stop: String,
        inTokens: Int = 900,
        outTokens: Int = 600,
    ): String =
        m.writeValueAsString(
            mapOf(
                "id" to "msg_test_2",
                "type" to "message",
                "role" to "assistant",
                "model" to "claude-opus-5",
                "stop_reason" to stop,
                "stop_sequence" to null,
                "content" to listOf(mapOf("type" to "text", "text" to text)),
                "usage" to mapOf("input_tokens" to inTokens, "output_tokens" to outTokens),
            ),
        )

    fun openAi(
        text: String,
        finish: String = "stop",
        cost: String? = null,
    ): String =
        m.writeValueAsString(
            mapOf(
                "id" to "chatcmpl-1",
                "model" to "test-model",
                "choices" to listOf(mapOf("index" to 0, "message" to mapOf("role" to "assistant", "content" to text), "finish_reason" to finish)),
                "usage" to (mapOf("prompt_tokens" to 100, "completion_tokens" to 40) + (cost?.let { mapOf("cost" to BigDecimal(it)) } ?: emptyMap())),
            ),
        )

    /** A Google Search grounded answer: queries, two web chunks and supports linking answer text to them. */
    fun geminiGrounded(text: String): String =
        m.writeValueAsString(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf(
                            "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to text))),
                            "finishReason" to "STOP",
                            "groundingMetadata" to
                                mapOf(
                                    "webSearchQueries" to listOf("chart champions crypto trading strategy"),
                                    "groundingChunks" to
                                        listOf(
                                            mapOf("web" to mapOf("uri" to "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc", "title" to "chartchampions.com")),
                                            mapOf("web" to mapOf("uri" to "https://example.org/crypto-trading-review", "title" to "example.org")),
                                        ),
                                    "groundingSupports" to
                                        listOf(
                                            mapOf("segment" to mapOf("text" to "They trade Bitcoin and Ethereum."), "groundingChunkIndices" to listOf(0, 1)),
                                            mapOf("segment" to mapOf("text" to "Entries follow retests of support."), "groundingChunkIndices" to listOf(0)),
                                        ),
                                ),
                        ),
                    ),
                "usageMetadata" to mapOf("promptTokenCount" to 300, "candidatesTokenCount" to 200, "thoughtsTokenCount" to 40),
                "modelVersion" to "gemini-2.5-flash",
            ),
        )

    fun gemini(
        text: String,
        finish: String,
    ): String =
        m.writeValueAsString(
            mapOf(
                "candidates" to listOf(mapOf("content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to text))), "finishReason" to finish)),
                "usageMetadata" to mapOf("promptTokenCount" to 80, "candidatesTokenCount" to 50, "thoughtsTokenCount" to 20),
                "modelVersion" to "test-model",
            ),
        )

    @Test
    fun `D-039 the replacement Gemini model is the latest alias, else the newest stable Flash`() {
        assertThat(AiClients.pickGeminiModel(listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-3.0-pro"))).isEqualTo("gemini-flash-latest")
        assertThat(AiClients.pickGeminiModel(listOf("gemini-2.5-flash", "gemini-3.0-flash", "gemini-3.5-flash-lite", "gemini-3.5-flash-image"))).isEqualTo("gemini-3.0-flash")
        assertThat(AiClients.pickGeminiModel(listOf("gemini-3.5-flash-preview-11-2026", "gemini-2.0-flash-lite"))).isEqualTo("gemini-3.5-flash-preview-11-2026")
        assertThat(AiClients.pickGeminiModel(listOf("gemini-3.0-pro", "text-embedding-004"))).isEqualTo("gemini-3.0-pro")
        assertThat(AiClients.pickGeminiModel(listOf("text-embedding-004"))).isNull()
    }
}
