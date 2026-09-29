package app.strategyforge.engine.api

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Answers the app's `/v1/...` calls in-process (D-031): the request never leaves the phone. The
 * app's API client keeps its idempotency keys, If-Match versions and problem handling unchanged.
 * Install it as the OkHttp client's only interceptor with base URL [BASE_URL].
 */
class LocalApiInterceptor(
    private val host: EngineHost,
    private val api: () -> LocalApi,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val text = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        val body = text?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        val local =
            LocalRequest(
                request.method,
                url.encodedPath,
                url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() },
                body,
                request.header("If-Match"),
            )
        val result = host.call { api().handle(local) }
        val builder =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(result.status)
                .message(if (result.status < 400) "OK" else "Error")
                .body(result.body.toResponseBody(result.contentType.toMediaTypeOrNull()))
                .header("Content-Type", result.contentType)
        result.headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    companion object {
        /** Base URL the app's API client uses on the phone; nothing is listening there. */
        const val BASE_URL = "https://engine.local"
    }
}
