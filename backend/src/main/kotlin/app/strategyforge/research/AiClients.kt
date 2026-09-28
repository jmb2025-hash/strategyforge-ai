package app.strategyforge.research

import app.strategyforge.providers.ProviderKind
import app.strategyforge.providers.ProviderType
import app.strategyforge.providers.ResolvedProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A single, stateless model call. The prompt text is data assembled by the server, never by the model. */
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
) : RuntimeException(message, cause)

/** Common adapter over AI providers (FR-030). Implementations never log or return the credential. */
interface AiClient {
    fun supports(type: ProviderType): Boolean

    fun complete(
        provider: ResolvedProvider,
        request: AiRequest,
    ): AiResponse
}

/** Default public endpoints; the owner may override `baseUrl` per provider configuration. */
object AiEndpoints {
    fun baseUrl(p: ResolvedProvider): String =
        (
            p.string("baseUrl") ?: when (p.type) {
                ProviderType.ANTHROPIC -> "https://api.anthropic.com"
                ProviderType.OPENAI -> "https://api.openai.com/v1"
                ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
                ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
                else -> error("${p.type} is not an AI provider")
            }
        ).trimEnd('/')

    fun timeout(p: ResolvedProvider): Duration = Duration.ofSeconds(p.int("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS).toLong())

    const val DEFAULT_TIMEOUT_SECONDS = 180
}

/** Shared JSON-over-HTTP plumbing and status classification for the non-SDK adapters. */
abstract class HttpAiClient(
    protected val mapper: ObjectMapper,
) : AiClient {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    protected fun post(
        p: ResolvedProvider,
        url: String,
        headers: Map<String, String>,
        body: Map<String, Any?>,
    ): Pair<JsonNode, String> {
        val b =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(AiEndpoints.timeout(p))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        headers.forEach { (k, v) -> b.header(k, v) }
        // The request timeout covers only the response headers; the deadline below bounds the whole body too.
        val future = http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
        val resp =
            try {
                future.get(AiEndpoints.timeout(p).toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw AiException(AiFailure.TIMEOUT, "The provider did not respond within ${AiEndpoints.timeout(p).seconds}s", cause = e)
            } catch (e: ExecutionException) {
                when (e.cause) {
                    is HttpTimeoutException -> throw AiException(AiFailure.TIMEOUT, "The provider did not respond within ${AiEndpoints.timeout(p).seconds}s", cause = e)
                    is IOException -> throw AiException(AiFailure.PROVIDER_ERROR, "Network error contacting the provider (${e.cause?.javaClass?.simpleName})", cause = e)
                    else -> throw AiException(AiFailure.PROVIDER_ERROR, "Unexpected transport failure (${e.cause?.javaClass?.simpleName})", cause = e)
                }
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw AiException(AiFailure.PROVIDER_ERROR, "The request was interrupted", cause = e)
            }
        val text = resp.body() ?: ""
        classify(resp.statusCode(), text)
        val json =
            try {
                mapper.readTree(text)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
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
        when {
            status in 200..299 -> return
            status == 401 || status == 403 -> throw AiException(AiFailure.AUTHENTICATION, "The provider rejected the credential (HTTP $status)", raw)
            status == 408 || status == 504 -> throw AiException(AiFailure.TIMEOUT, "The provider timed out (HTTP $status)", raw)
            status == 429 -> throw AiException(AiFailure.RATE_LIMITED, "The provider rate limit or quota was exceeded (HTTP 429)", raw)
            status in 400..499 -> throw AiException(AiFailure.BAD_REQUEST, "The provider rejected the request (HTTP $status)", raw)
            else -> throw AiException(AiFailure.PROVIDER_ERROR, "The provider failed (HTTP $status)", raw)
        }
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
    }
}

/**
 * OpenAI Chat Completions and the OpenAI-compatible OpenRouter endpoint. Neither adapter offers
 * retrieval, so sources are never claimed for their output (FR-033).
 */
@Component
class OpenAiCompatibleClient(
    mapper: ObjectMapper,
) : HttpAiClient(mapper) {
    override fun supports(type: ProviderType) = type == ProviderType.OPENAI || type == ProviderType.OPENROUTER

    override fun complete(
        provider: ResolvedProvider,
        request: AiRequest,
    ): AiResponse {
        require(!request.retrieval) { "${provider.type} adapter does not support retrieval" }
        val model = provider.string("model")!!
        val tokenParam = if (provider.type == ProviderType.OPENAI) "max_completion_tokens" else "max_tokens"
        val body =
            mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "system", "content" to request.system), mapOf("role" to "user", "content" to request.prompt)),
                tokenParam to request.maxOutputTokens,
            )
        val (json, raw) = post(provider, AiEndpoints.baseUrl(provider) + "/chat/completions", mapOf("Authorization" to "Bearer ${provider.credential}"), body)
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

/** Google Gemini `generateContent`. Grounding is not enabled by this adapter, so no sources are claimed. */
@Component
class GeminiClient(
    mapper: ObjectMapper,
) : HttpAiClient(mapper) {
    override fun supports(type: ProviderType) = type == ProviderType.GEMINI

    override fun complete(
        provider: ResolvedProvider,
        request: AiRequest,
    ): AiResponse {
        require(!request.retrieval) { "Gemini adapter does not support retrieval" }
        val model = provider.string("model")!!
        val url = AiEndpoints.baseUrl(provider) + "/v1beta/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent"
        val body =
            mapOf(
                "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to request.system))),
                "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to request.prompt)))),
                "generationConfig" to mapOf("maxOutputTokens" to request.maxOutputTokens),
            )
        val (json, raw) = post(provider, url, mapOf("x-goog-api-key" to (provider.credential ?: "")), body)
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
        return AiResponse(
            text,
            emptyList(),
            requireLong(usage?.get("promptTokenCount"), raw, "usageMetadata.promptTokenCount"),
            requireLong(usage?.get("candidatesTokenCount"), raw, "usageMetadata.candidatesTokenCount") + thoughts,
            0,
            finish,
            json["modelVersion"]?.asText() ?: model,
            raw.take(RAW_LIMIT),
            null,
            mapOf("maxOutputTokens" to request.maxOutputTokens),
        )
    }
}

/** Resolves the adapter for a provider configuration; unknown or non-AI providers fail closed. */
@Component
class AiClients(
    private val clients: List<AiClient>,
) {
    fun forType(type: ProviderType): AiClient {
        require(type.kind == ProviderKind.AI) { "$type is not an AI provider" }
        return clients.firstOrNull { it.supports(type) } ?: error("No AI adapter for $type")
    }

    /** Retrieval with citations is offered only by adapters that implement it and only when enabled. */
    fun retrievalAvailable(p: ResolvedProvider): Boolean = p.type == ProviderType.ANTHROPIC && p.bool("webSearchEnabled")
}
