package app.strategyforge.android.core.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** Shared JSON configuration: unknown fields are ignored so older apps keep working against newer backends. */
val SfJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

/** Failures the UI distinguishes; messages never contain credentials or tokens. */
sealed class ApiError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No server URL has been configured yet. */
    class NotConfigured : ApiError("The backend address is not configured")

    /** The network or server could not be reached; cached data may be shown (Offline state). */
    class Offline(
        cause: Throwable?,
    ) : ApiError("The backend could not be reached", cause)

    /** The session is missing, expired or revoked; the owner must sign in again. */
    class Unauthorized(
        val code: String?,
    ) : ApiError("Sign in again to continue")

    /** An RFC 7807 problem from the backend. */
    class Http(
        val status: Int,
        val code: String?,
        val title: String?,
        val detail: String?,
        val properties: JsonObject?,
    ) : ApiError(detail ?: title ?: "Request failed (HTTP $status)") {
        val permissionDenied get() = status == 403
        val recentAuthRequired get() = code == "recent-authentication-required"

        /** The form field the problem is about (symbol, quantity, limitPrice, ...), when the engine names one (D-065). */
        val field: String? get() = (properties?.get("field") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** The server answered with something the app cannot interpret. */
    class Malformed(
        cause: Throwable?,
    ) : ApiError("The backend returned an unexpected response", cause)
}

/** The raw result of a call: parsed JSON body plus the ETag used for optimistic concurrency. */
data class ApiResponse(
    val status: Int,
    val body: JsonElement,
    val etag: String?,
)

/** A non-JSON download (CSV/JSON export) with the response headers the backend adds. */
class Download(
    val bytes: ByteArray,
    val contentType: String?,
    val fileName: String?,
    val headers: Map<String, String>,
)

/** Where the session token lives (Android: Keystore-encrypted storage). */
interface TokenStore {
    fun token(): String?

    fun save(token: String?)
}

/**
 * Minimal HTTP client for the StrategyForge backend. Every POST carries an Idempotency-Key; network
 * failures on mutations are retried with the SAME key, so the backend executes each action at most
 * once (NFR-004, NFR-007). The backend is authoritative: nothing here decides trading outcomes.
 */
class ApiClient(
    private val baseUrl: () -> String?,
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelaysMs: List<Long> = listOf(300, 1000),
) {
    suspend fun get(path: String): ApiResponse = call("GET", path, null, idempotencyKey = null, ifMatch = null)

    suspend fun <T> get(
        path: String,
        strategy: DeserializationStrategy<T>,
    ): T = decode(get(path).body, strategy)

    /** GET of a file-like response (exports). Errors are mapped exactly like JSON calls. */
    suspend fun download(path: String): Download {
        val base = baseUrl()?.trimEnd('/') ?: throw ApiError.NotConfigured()
        val url = (base + path).toHttpUrlOrNull() ?: throw ApiError.NotConfigured()
        var attempt = 0
        while (true) {
            try {
                return withContext(io) { executeDownload(url.toString()) }
            } catch (e: IOException) {
                if (attempt >= retryDelaysMs.size) throw ApiError.Offline(e)
                delay(retryDelaysMs[attempt++])
            }
        }
    }

    /** POST with an idempotency key generated once and reused across retries. */
    suspend fun post(
        path: String,
        body: JsonElement? = null,
        idempotencyKey: String = newKey(),
    ): ApiResponse = call("POST", path, body ?: JsonObject(emptyMap()), idempotencyKey, null)

    suspend fun put(
        path: String,
        body: JsonElement,
        ifMatch: String? = null,
    ): ApiResponse = call("PUT", path, body, null, ifMatch)

    suspend fun patch(
        path: String,
        body: JsonElement,
        ifMatch: String? = null,
    ): ApiResponse = call("PATCH", path, body, null, ifMatch)

    suspend fun delete(path: String): ApiResponse = call("DELETE", path, null, null, null)

    fun <T> decode(
        element: JsonElement,
        strategy: DeserializationStrategy<T>,
    ): T =
        try {
            SfJson.decodeFromJsonElement(strategy, element)
        } catch (e: SerializationException) {
            throw ApiError.Malformed(e)
        } catch (e: IllegalArgumentException) {
            throw ApiError.Malformed(e)
        }

    private suspend fun call(
        method: String,
        path: String,
        body: JsonElement?,
        idempotencyKey: String?,
        ifMatch: String?,
    ): ApiResponse {
        val base = baseUrl()?.trimEnd('/') ?: throw ApiError.NotConfigured()
        val url = (base + path).toHttpUrlOrNull() ?: throw ApiError.NotConfigured()
        var attempt = 0
        while (true) {
            try {
                return withContext(io) { execute(method, url.toString(), body, idempotencyKey, ifMatch) }
            } catch (e: IOException) {
                // Retrying is safe: GETs are read-only and mutations reuse their idempotency key.
                if (attempt >= retryDelaysMs.size || (method != "GET" && idempotencyKey == null)) throw ApiError.Offline(e)
                delay(retryDelaysMs[attempt++])
            }
        }
    }

    private fun execute(
        method: String,
        url: String,
        body: JsonElement?,
        idempotencyKey: String?,
        ifMatch: String?,
    ): ApiResponse {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        tokens.token()?.let { b.header("Authorization", "Bearer $it") }
        idempotencyKey?.let { b.header("Idempotency-Key", it) }
        ifMatch?.let { b.header("If-Match", it) }
        val requestBody = body?.let { SfJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }
        b.method(method, requestBody)
        http.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json =
                if (text.isBlank()) {
                    JsonNull
                } else {
                    try {
                        SfJson.parseToJsonElement(text)
                    } catch (e: SerializationException) {
                        if (resp.isSuccessful) throw ApiError.Malformed(e) else JsonNull
                    }
                }
            if (resp.isSuccessful) return ApiResponse(resp.code, json, resp.header("ETag"))
            throw failure(resp.code, json)
        }
    }

    private fun executeDownload(url: String): Download {
        val b = Request.Builder().url(url).header("Accept", "text/csv, application/json")
        tokens.token()?.let { b.header("Authorization", "Bearer $it") }
        http.newCall(b.get().build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val json = runCatching { SfJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrDefault(JsonNull)
                throw failure(resp.code, json)
            }
            val name = resp.header("Content-Disposition")?.let { FILE_NAME.find(it)?.groupValues?.get(1) }
            return Download(bytes, resp.header("Content-Type"), name, resp.headers.toMap())
        }
    }

    private fun failure(
        status: Int,
        json: JsonElement,
    ): ApiError {
        val problem = json as? JsonObject
        val code = problem?.get("code")?.jsonPrimitive?.contentOrNull
        if (status == 401) {
            tokens.save(null)
            return ApiError.Unauthorized(code)
        }
        return ApiError.Http(
            status,
            code,
            problem?.get("title")?.jsonPrimitive?.contentOrNull,
            problem?.get("detail")?.jsonPrimitive?.contentOrNull,
            problem,
        )
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val FILE_NAME = Regex("filename=\"?([A-Za-z0-9._-]+)\"?")
    }
}

/** Server address rules: HTTPS always, except loopback/emulator hosts in debug builds (private network over TLS). */
object ServerUrl {
    fun validate(
        input: String,
        allowInsecureLocal: Boolean,
    ): String? {
        val url = input.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        val local = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        if (url.scheme != "https" && !(allowInsecureLocal && local)) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null) return null
        return url.toString().trimEnd('/')
    }
}
