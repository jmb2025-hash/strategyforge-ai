package app.strategyforge.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

data class Resp(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    val json: JsonNode by lazy { if (body.isBlank()) TestHttp.mapper.nullNode() else TestHttp.mapper.readTree(body) }

    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, true) }
            ?.value
            ?.firstOrNull()

    override fun toString(): String = "HTTP $status $body"
}

/** Minimal real-HTTP client for API, contract and end-to-end tests. */
class TestHttp(
    private val base: String,
    var token: String? = null,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ) = send("GET", path, null, headers)

    fun post(
        path: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        idem: String? = UUID.randomUUID().toString(),
    ) = send("POST", path, body, headers + (if (idem != null) mapOf("Idempotency-Key" to idem) else emptyMap()))

    fun put(
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap(),
    ) = send("PUT", path, body, headers)

    fun patch(
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap(),
    ) = send("PATCH", path, body, headers)

    fun delete(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ) = send("DELETE", path, null, headers)

    fun send(
        method: String,
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap(),
    ): Resp {
        val payload =
            when (body) {
                null -> HttpRequest.BodyPublishers.noBody()
                is String -> HttpRequest.BodyPublishers.ofString(body)
                else -> HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
            }
        val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).method(method, payload)
        if (body != null) b.header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        val resp = Resp(r.statusCode(), r.body(), r.headers().map())
        Contract.record(method, path, resp)
        return resp
    }

    companion object {
        val mapper: ObjectMapper = jacksonObjectMapper()
    }
}
