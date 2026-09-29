package app.strategyforge.engine.research

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Duration

/** A single, stateless model call. The prompt text is data assembled by the engine, never by the model. */
data class AiRequest(
    val system: String,
    val prompt: String,
    val maxOutputTokens: Int,
    val retrieval: Boolean,
    val maxSearches: Int = 0,
)

data class AiSource(
    val url: String,
    val title: String?,
    val citedText: String?,
    val pageAge: String? = null,
)

data class AiResponse(
    val text: String,
    val sources: List<AiSource>,
    val inputTokens: Long,
    val outputTokens: Long,
    val searchRequests: Long,
    val stopReason: String,
    val model: String,
    val raw: String,
    /** Cost reported by the provider itself, when it reports one (preferred over the estimate, D-010). */
    val reportedCostUsd: BigDecimal? = null,
    val parameters: Map<String, Any?> = emptyMap(),
)

enum class AiFailure { TIMEOUT, RATE_LIMITED, AUTHENTICATION, BAD_REQUEST, PROVIDER_ERROR, MALFORMED_RESPONSE, REFUSED, TRUNCATED, INCOMPLETE }

class AiException(
    val failure: AiFailure,
    message: String,
    val raw: String? = null,
    cause: Throwable? = null,
    /** The provider's HTTP status when it answered with an error (404 means the model is unknown). */
    val httpStatus: Int? = null,
) : RuntimeException(message, cause)

/** Common adapter over AI providers (FR-030). Implementations never log or return the key. */
interface AiClient {
    fun supports(type: AiProviderType): Boolean

    fun complete(
        provider: AiProviderConfig,
        request: AiRequest,
    ): AiResponse
}

/** Default public endpoints; the owner may override `baseUrl` per provider. */
object AiEndpoints {
    fun baseUrl(p: AiProviderConfig): String =
        (
            p.string("baseUrl") ?: when (p.type) {
                AiProviderType.ANTHROPIC -> "https://api.anthropic.com"
                AiProviderType.OPENAI -> "https://api.openai.com/v1"
                AiProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
                AiProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
            }
        ).trimEnd('/')

    fun timeout(p: AiProviderConfig): Duration = Duration.ofSeconds(p.int("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS).toLong())

    const val DEFAULT_TIMEOUT_SECONDS = 180
}

/** Shared JSON-over-HTTP plumbing and status classification for the REST adapters. */
abstract class HttpAiClient(
    private val http: OkHttpClient,
) : AiClient {
    protected val mapper: ObjectMapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    protected fun post(
        p: AiProviderConfig,
        url: String,
        headers: Map<String, String>,
        body: Map<String, Any?>,
    ): Pair<JsonNode, String> {
        val timeout = AiEndpoints.timeout(p)
        val client =
            http
                .newBuilder()
                .callTimeout(timeout)
                .readTimeout(timeout)
                .build()
        val b =
            Request
                .Builder()
                .url(url)
                .post(mapper.writeValueAsString(body).toRequestBody(JSON))
        headers.forEach { (k, v) -> b.header(k, v) }
        val (status, text) =
            try {
                client.newCall(b.build()).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: InterruptedIOException) {
                throw AiException(AiFailure.TIMEOUT, "The provider did not respond within ${timeout.seconds}s", cause = e)
            } catch (e: IOException) {
                throw AiException(AiFailure.PROVIDER_ERROR, "Network error contacting the provider (${e.javaClass.simpleName})", cause = e)
            }
        classify(status, text)
        val json =
            try {
                mapper.readTree(text)
            } catch (e: JsonProcessingException) {
                throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider returned a response that is not JSON", text.take(RAW_LIMIT), e)
            }
        if (json == null || !json.isObject) throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider returned an unexpected JSON value", text.take(RAW_LIMIT))
        return json to text
    }

    private fun classify(
        status: Int,
        body: String,
    ) {
        val raw = body.take(RAW_LIMIT)
        if (status in 200..299) return
        // The provider's own explanation (for example "models/x is not found"), shortened; never contains the key.
        val said = providerMessage(body)?.let { ": $it" } ?: ""
        when {
            status == 401 || status == 403 -> throw AiException(AiFailure.AUTHENTICATION, "The provider rejected the key (HTTP $status)$said", raw, httpStatus = status)
            status == 408 || status == 504 -> throw AiException(AiFailure.TIMEOUT, "The provider timed out (HTTP $status)", raw, httpStatus = status)
            status == 429 -> throw AiException(AiFailure.RATE_LIMITED, "The provider rate limit or free-tier quota was exceeded (HTTP 429)$said", raw, httpStatus = status)
            status == 404 -> throw AiException(AiFailure.BAD_REQUEST, "The provider does not offer this model to your key (HTTP 404)$said", raw, httpStatus = status)
            status in 400..499 -> throw AiException(AiFailure.BAD_REQUEST, "The provider rejected the request (HTTP $status)$said", raw, httpStatus = status)
            else -> throw AiException(AiFailure.PROVIDER_ERROR, "The provider failed (HTTP $status)", raw, httpStatus = status)
        }
    }

    private fun providerMessage(body: String): String? =
        runCatching {
            val n = mapper.readTree(body)
            (n?.get("error")?.get("message") ?: n?.get("error")?.takeIf { it.isTextual } ?: n?.get("message"))?.asText()
        }.getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MESSAGE_LIMIT)

    /** A GET returning JSON, with the same error handling as [post]. */
    protected fun getJson(
        p: AiProviderConfig,
        url: String,
        headers: Map<String, String>,
    ): JsonNode {
        val timeout = AiEndpoints.timeout(p)
        val client =
            http
                .newBuilder()
                .callTimeout(timeout)
                .readTimeout(timeout)
                .build()
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        val (status, text) =
            try {
                client.newCall(b.build()).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: IOException) {
                throw AiException(AiFailure.PROVIDER_ERROR, "Network error contacting the provider (${e.javaClass.simpleName})", cause = e)
            }
        classify(status, text)
        return runCatching { mapper.readTree(text) }.getOrNull()?.takeIf { it.isObject }
            ?: throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider returned an unexpected response", text.take(RAW_LIMIT))
    }

    protected fun requireText(
        node: JsonNode?,
        raw: String,
        what: String,
    ): String = node?.takeIf { it.isTextual }?.asText() ?: throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response has no $what", raw.take(RAW_LIMIT))

    protected fun requireLong(
        node: JsonNode?,
        raw: String,
        what: String,
    ): Long = node?.takeIf { it.canConvertToLong() }?.asLong() ?: throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response has no $what", raw.take(RAW_LIMIT))

    companion object {
        const val RAW_LIMIT = 200_000
        private const val MESSAGE_LIMIT = 300
        private val JSON = "application/json".toMediaType()
    }
}

/**
 * OpenAI Chat Completions and the OpenAI-compatible OpenRouter endpoint. Neither adapter offers
 * retrieval, so sources are never claimed for their output (FR-033).
 */
class OpenAiCompatibleClient(
    http: OkHttpClient,
) : HttpAiClient(http) {
    override fun supports(type: AiProviderType) = type == AiProviderType.OPENAI || type == AiProviderType.OPENROUTER

    override fun complete(
        provider: AiProviderConfig,
        request: AiRequest,
    ): AiResponse {
        require(!request.retrieval) { "${provider.type} adapter does not support retrieval" }
        val model = provider.string("model")!!
        val tokenParam = if (provider.type == AiProviderType.OPENAI) "max_completion_tokens" else "max_tokens"
        val body =
            mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "system", "content" to request.system), mapOf("role" to "user", "content" to request.prompt)),
                tokenParam to request.maxOutputTokens,
            )
        val (json, raw) = post(provider, AiEndpoints.baseUrl(provider) + "/chat/completions", mapOf("Authorization" to "Bearer ${provider.key}"), body)
        val choice = json["choices"]?.takeIf { it.isArray && it.size() > 0 }?.get(0) ?: throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response has no choices", raw.take(RAW_LIMIT))
        val finish = choice["finish_reason"]?.asText() ?: "unknown"
        if (finish == "content_filter") throw AiException(AiFailure.REFUSED, "The provider declined to answer (content filter)", raw.take(RAW_LIMIT))
        val text = requireText(choice["message"]?.get("content"), raw, "message content")
        if (finish == "length") throw AiException(AiFailure.TRUNCATED, "The response hit the output-token ceiling and is incomplete", raw.take(RAW_LIMIT))
        val usage = json["usage"]
        return AiResponse(
            text,
            emptyList(),
            requireLong(usage?.get("prompt_tokens"), raw, "usage.prompt_tokens"),
            requireLong(usage?.get("completion_tokens"), raw, "usage.completion_tokens"),
            0,
            finish,
            json["model"]?.asText() ?: model,
            raw.take(RAW_LIMIT),
            usage?.get("cost")?.takeIf { it.isNumber }?.decimalValue(),
            mapOf(tokenParam to request.maxOutputTokens),
        )
    }
}

/**
 * Google Gemini `generateContent` (the default, free-tier friendly provider). With retrieval, the
 * Google Search grounding tool is attached and the sources come from `groundingMetadata`; without
 * it, no sources are claimed (FR-033).
 */
class GeminiClient(
    http: OkHttpClient,
) : HttpAiClient(http) {
    override fun supports(type: AiProviderType) = type == AiProviderType.GEMINI

    override fun complete(
        provider: AiProviderConfig,
        request: AiRequest,
    ): AiResponse {
        val model = provider.string("model")!!
        val url = AiEndpoints.baseUrl(provider) + "/v1beta/models/" + URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val generation = mutableMapOf<String, Any?>("maxOutputTokens" to request.maxOutputTokens)
        // Thinking tokens count against the output ceiling on 2.5 models; keep room for the answer.
        if (model.startsWith("gemini-2.5")) generation["thinkingConfig"] = mapOf("thinkingBudget" to minOf(THINKING_BUDGET, request.maxOutputTokens / 4))
        val body =
            buildMap<String, Any?> {
                put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to request.system))))
                put("contents", listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to request.prompt)))))
                put("generationConfig", generation)
                if (request.retrieval) put("tools", listOf(mapOf("google_search" to emptyMap<String, Any>())))
            }
        val (json, raw) = post(provider, url, mapOf("x-goog-api-key" to (provider.key ?: "")), body)
        json["promptFeedback"]?.get("blockReason")?.let { throw AiException(AiFailure.REFUSED, "The provider blocked the prompt (${it.asText()})", raw.take(RAW_LIMIT)) }
        val candidate = json["candidates"]?.takeIf { it.isArray && it.size() > 0 }?.get(0) ?: throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response has no candidates", raw.take(RAW_LIMIT))
        val finish = candidate["finishReason"]?.asText() ?: "UNKNOWN"
        if (finish in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT")) throw AiException(AiFailure.REFUSED, "The provider declined to answer ($finish)", raw.take(RAW_LIMIT))
        val parts = candidate["content"]?.get("parts")?.takeIf { it.isArray } ?: throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response has no content parts", raw.take(RAW_LIMIT))
        // Thought parts are not part of the answer.
        val text = parts.filter { it["thought"]?.asBoolean() != true }.mapNotNull { it["text"]?.asText() }.joinToString("")
        if (text.isBlank()) throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response contains no text", raw.take(RAW_LIMIT))
        if (finish == "MAX_TOKENS") throw AiException(AiFailure.TRUNCATED, "The response hit the output-token ceiling and is incomplete", raw.take(RAW_LIMIT))
        val usage = json["usageMetadata"]
        val thoughts = usage?.get("thoughtsTokenCount")?.asLong() ?: 0
        val grounding = candidate["groundingMetadata"]
        val sources = if (request.retrieval) groundingSources(grounding) else emptyList()
        val searched = (grounding?.get("webSearchQueries")?.size() ?: 0) > 0 || sources.isNotEmpty()
        return AiResponse(
            text,
            sources,
            requireLong(usage?.get("promptTokenCount"), raw, "usageMetadata.promptTokenCount"),
            // A reply without text candidates (for example a search-only turn) reports no candidate tokens.
            (usage?.get("candidatesTokenCount")?.asLong() ?: 0) + thoughts,
            // Grounding is billed per grounded prompt, not per query.
            if (searched) 1 else 0,
            finish,
            json["modelVersion"]?.asText() ?: model,
            raw.take(RAW_LIMIT),
            null,
            mapOf("maxOutputTokens" to request.maxOutputTokens, "googleSearch" to request.retrieval),
        )
    }

    /** Models this key may call with `generateContent`, without the "models/" prefix. */
    fun availableModels(provider: AiProviderConfig): List<String> {
        val json = getJson(provider, AiEndpoints.baseUrl(provider) + "/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to (provider.key ?: "")))
        return json["models"]
            ?.filter { m -> m["supportedGenerationMethods"]?.any { it.asText() == "generateContent" } ?: true }
            ?.mapNotNull { it["name"]?.asText()?.removePrefix("models/") }
            .orEmpty()
    }

    /** Web sources from `groundingChunks`, with the answer text each one supports where given. */
    private fun groundingSources(grounding: JsonNode?): List<AiSource> {
        val chunks = grounding?.get("groundingChunks")?.takeIf { it.isArray } ?: return emptyList()
        val supported = mutableMapOf<Int, MutableList<String>>()
        grounding["groundingSupports"]?.forEach { s ->
            val segment = s["segment"]?.get("text")?.asText() ?: return@forEach
            s["groundingChunkIndices"]?.forEach { i -> supported.getOrPut(i.asInt()) { mutableListOf() } += segment }
        }
        return chunks.mapIndexedNotNull { i, c ->
            val web = c["web"] ?: return@mapIndexedNotNull null
            val uri = web["uri"]?.asText()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapIndexedNotNull null
            AiSource(uri, web["title"]?.asText(), supported[i]?.joinToString(" … ")?.take(MAX_CITED))
        }
    }

    private companion object {
        const val THINKING_BUDGET = 2048
        const val MAX_CITED = 1000
    }
}

/** Resolves the adapter for a provider; unknown providers fail closed. */
class AiClients(
    private val clients: List<AiClient>,
) {
    fun forType(type: AiProviderType): AiClient = clients.firstOrNull { it.supports(type) } ?: error("No AI adapter for $type")

    /** Retrieval with citations is offered only by adapters that implement it and only when enabled. */
    fun retrievalAvailable(p: AiProviderConfig): Boolean = p.webSearchEnabled()

    companion object {
        /**
         * The best general-purpose model from a key's list (D-039): the "latest Flash" alias, else the
         * newest stable Flash, else the newest Flash variant, else any Gemini text model. Lite, image,
         * speech, live, embedding and experimental models are avoided.
         */
        fun pickGeminiModel(names: List<String>): String? {
            if ("gemini-flash-latest" in names) return "gemini-flash-latest"
            val avoid = Regex("lite|image|tts|audio|live|embed|exp|vision|learnlm|aqa|robotics|computer")

            fun version(n: String) =
                Regex("^gemini-(\\d+(?:\\.\\d+)?)")
                    .find(n)
                    ?.groupValues
                    ?.get(1)
                    ?.toDoubleOrNull() ?: 0.0
            val usable = names.filter { it.startsWith("gemini-") && !avoid.containsMatchIn(it) }
            val stable = usable.filter { Regex("^gemini-\\d+(\\.\\d+)?-flash$").matches(it) }
            val flash = usable.filter { it.contains("flash") }
            return (stable.maxByOrNull(::version) ?: flash.maxByOrNull(::version) ?: usable.maxByOrNull(::version))
        }

        fun default(http: OkHttpClient = defaultHttp()): AiClients = AiClients(listOf(GeminiClient(http), OpenAiCompatibleClient(http), AnthropicAiClient()))

        fun defaultHttp(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(false)
                .build()
    }
}
