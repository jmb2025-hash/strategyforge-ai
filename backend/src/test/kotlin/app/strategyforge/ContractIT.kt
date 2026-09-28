package app.strategyforge

import app.strategyforge.support.Contract
import app.strategyforge.support.IntegrationTest
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.parser.OpenAPIV3Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.io.File

/**
 * RG-05 / NFR-004: the published OpenAPI 3.1 contract is generated from the running backend,
 * written to contracts/openapi.json (CI fails if it is not committed), parses without errors, and
 * documents every operation the integration suite actually called.
 */
@Order(Int.MAX_VALUE)
class ContractIT : IntegrationTest() {
    private val mapper =
        TestHttp.mapper
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)

    @Test
    fun `RG-05 OpenAPI 3_1 contract is current, valid and covers every exercised operation`() {
        val h = TestOwner.client(baseUrl)
        val r = h.get("/v3/api-docs")
        assertThat(r.status).isEqualTo(200)
        val spec = r.json as ObjectNode
        assertThat(spec["openapi"].asText()).startsWith("3.1")
        // Server URLs depend on the random test port; the committed contract is host-neutral.
        spec.remove("servers")
        val canonical = mapper.writeValueAsString(mapper.treeToValue(spec, Any::class.java)) + "\n"
        val file = File("../contracts/openapi.json")
        file.parentFile.mkdirs()
        file.writeText(canonical)

        val parsed = OpenAPIV3Parser().readContents(canonical, null, null)
        assertThat(parsed.messages).`as`("OpenAPI parse messages").isEmpty()
        assertThat(parsed.openAPI.paths).isNotEmpty()

        val operations = operations(spec)
        val undocumented =
            Contract.observed
                // Real-money probes are rejected by the safety filter and must NOT exist as routes (MS-20).
                .filter { it.path.startsWith("/v1/") && it.status != 404 && !it.body.contains("real-money-prohibited") }
                .map { it.method.lowercase() to it.path }
                .distinct()
                .filter { (m, p) -> operations.none { (om, template) -> om == m && matches(template, p) } }
        assertThat(undocumented).`as`("operations called by tests but missing from the contract").isEmpty()

        // Every documented operation declares a success response and error responses use problem JSON.
        spec["paths"].fields().forEach { (path, item) ->
            item.fields().forEach { (method, op) ->
                val codes =
                    op["responses"]
                        ?.fieldNames()
                        ?.asSequence()
                        ?.toList()
                        .orEmpty()
                assertThat(codes.any { it.startsWith("2") }).`as`("$method $path has a 2xx response").isTrue()
            }
        }
        val failures = Contract.observed.filter { it.path.startsWith("/v1/") && it.status >= 400 && it.body.isNotBlank() }
        failures.forEach { f ->
            val body = runCatching { TestHttp.mapper.readTree(f.body) }.getOrNull()
            assertThat(body?.has("code")).`as`("${f.method} ${f.path} ${f.status} returns a problem document").isTrue()
            assertThat(f.contentType).`as`("${f.method} ${f.path} content type").contains("application/problem+json")
        }
    }

    private fun operations(spec: JsonNode): List<Pair<String, String>> =
        spec["paths"]
            .fields()
            .asSequence()
            .flatMap { (path, item) -> item.fieldNames().asSequence().map { it to path } }
            .toList()

    private fun matches(
        template: String,
        path: String,
    ): Boolean {
        val t = template.trimEnd('/').split('/')
        val p = path.trimEnd('/').split('/')
        return t.size == p.size && t.zip(p).all { (a, b) -> (a.startsWith("{") && a.endsWith("}")) || a == b }
    }
}
