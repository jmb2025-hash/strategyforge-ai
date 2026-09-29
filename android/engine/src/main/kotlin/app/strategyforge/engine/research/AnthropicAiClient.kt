package app.strategyforge.engine.research

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.InterruptedIOException

/**
 * Anthropic Messages API through the official Java SDK. Optional web search (server tool) supplies
 * cited sources (FR-033). Refusals, truncation and paused turns are failures, never partial answers.
 */
class AnthropicAiClient : AiClient {
    override fun supports(type: AiProviderType) = type == AiProviderType.ANTHROPIC

    override fun complete(
        provider: AiProviderConfig,
        request: AiRequest,
    ): AiResponse {
        val model = provider.string("model")!!
        val fallback = provider.string("serverSideFallback")?.toBoolean() ?: (model in FALLBACK_MODELS)
        val b =
            MessageCreateParams
                .builder()
                .model(model)
                .maxTokens(request.maxOutputTokens.toLong())
                .system(request.system)
                .addUserMessage(request.prompt)
        if (request.retrieval) b.addTool(BetaWebSearchTool20260209.builder().maxUses(request.maxSearches.toLong()).build())
        if (fallback) {
            // Server-side refusal fallback: the server routes a refused request to a suitable model.
            b.addBeta(FALLBACK_BETA)
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val client =
            AnthropicOkHttpClient
                .builder()
                .apiKey(provider.key ?: throw AiException(AiFailure.AUTHENTICATION, "No API key configured"))
                .baseUrl(AiEndpoints.baseUrl(provider))
                .timeout(AiEndpoints.timeout(provider))
                .maxRetries(1)
                .build()
        val message =
            try {
                client.beta().messages().create(b.build())
            } catch (e: UnauthorizedException) {
                throw AiException(AiFailure.AUTHENTICATION, "The provider rejected the key (HTTP 401)", cause = e)
            } catch (e: PermissionDeniedException) {
                throw AiException(AiFailure.AUTHENTICATION, "The key is not permitted to use this model (HTTP 403)", cause = e)
            } catch (e: RateLimitException) {
                throw AiException(AiFailure.RATE_LIMITED, "The provider rate limit was exceeded (HTTP 429)", cause = e)
            } catch (e: BadRequestException) {
                throw AiException(AiFailure.BAD_REQUEST, "The provider rejected the request (HTTP 400)", e.body().toString(), cause = e)
            } catch (e: NotFoundException) {
                throw AiException(AiFailure.BAD_REQUEST, "The model '$model' was not found (HTTP 404)", cause = e)
            } catch (e: UnprocessableEntityException) {
                throw AiException(AiFailure.BAD_REQUEST, "The provider rejected the request (HTTP 422)", e.body().toString(), cause = e)
            } catch (e: AnthropicServiceException) {
                val failure = if (e.statusCode() == 408 || e.statusCode() == 504) AiFailure.TIMEOUT else AiFailure.PROVIDER_ERROR
                throw AiException(failure, "The provider failed (HTTP ${e.statusCode()})", e.body().toString(), cause = e)
            } catch (e: AnthropicIoException) {
                val timeout = causedByTimeout(e)
                throw AiException(if (timeout) AiFailure.TIMEOUT else AiFailure.PROVIDER_ERROR, if (timeout) "The provider did not respond in time" else "Network error contacting the provider", cause = e)
            } catch (e: AnthropicInvalidDataException) {
                throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider returned a malformed response", cause = e)
            } finally {
                client.close()
            }
        return toResponse(message, model, request, fallback)
    }

    private fun toResponse(
        message: BetaMessage,
        model: String,
        request: AiRequest,
        fallback: Boolean,
    ): AiResponse {
        val raw =
            try {
                jsonMapper().writeValueAsString(message)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response could not be recorded", cause = e)
            }
        val stop = message.stopReason().orElse(null)
        when (stop) {
            BetaStopReason.REFUSAL -> throw AiException(AiFailure.REFUSED, "The model declined to answer", raw)
            BetaStopReason.MAX_TOKENS -> throw AiException(AiFailure.TRUNCATED, "The response hit the output-token ceiling and is incomplete", raw)
            BetaStopReason.PAUSE_TURN -> throw AiException(AiFailure.INCOMPLETE, "The provider paused the turn before finishing; run the research again", raw)
            BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> throw AiException(AiFailure.TRUNCATED, "The request exceeded the model context window", raw)
            else -> Unit
        }
        val text = StringBuilder()
        val sources = linkedMapOf<String, AiSource>()
        for (block in message.content()) {
            block.text().ifPresent { t ->
                text.append(t.text())
                t.citations().ifPresent { cs ->
                    cs.forEach { c ->
                        c.webSearchResultLocation().ifPresent { w ->
                            sources.putIfAbsent(w.url(), AiSource(w.url(), w.title().orElse(null), w.citedText()))
                        }
                    }
                }
            }
        }
        if (text.isBlank()) throw AiException(AiFailure.MALFORMED_RESPONSE, "The provider response contains no text", raw)
        val usage = message.usage()
        return AiResponse(
            text.toString(),
            sources.values.toList(),
            usage.inputTokens(),
            usage.outputTokens(),
            usage.serverToolUse().map { it.webSearchRequests() }.orElse(0L),
            stop?.toString() ?: "unknown",
            message.model().toString(),
            raw,
            null,
            mapOf("max_tokens" to request.maxOutputTokens, "web_search_max_uses" to request.maxSearches.takeIf { request.retrieval }, "fallbacks" to if (fallback) "default" else null, "requestedModel" to model),
        )
    }

    private fun causedByTimeout(t: Throwable): Boolean = generateSequence(t) { it.cause }.any { it is InterruptedIOException }

    companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        val FALLBACK_MODELS = setOf("claude-opus-5", "claude-fable-5-1")
    }
}
