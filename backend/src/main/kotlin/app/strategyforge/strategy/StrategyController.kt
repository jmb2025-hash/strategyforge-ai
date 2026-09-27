package app.strategyforge.strategy

import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class StrategyContentRequest(
    val content: JsonNode,
)

data class StrategyCloneRequest(
    val name: String? = null,
)

data class StrategyReasonRequest(
    val reason: String? = null,
)

@RestController
@RequestMapping("/v1/strategies")
@Tag(name = "Strategies")
class StrategyController(
    private val strategies: StrategyService,
    private val idempotency: IdempotencyService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
    ) = strategies.list(includeArchived)

    @PostMapping
    fun create(
        @RequestBody req: StrategyContentRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("strategy-create", key, req, HttpStatus.CREATED) { strategies.create(req.content) }

    /** Raw strategy file import; the bytes are validated for size, encoding, JSON, schema and prohibited content (FR-041). */
    @PostMapping("/import", consumes = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun import(
        @RequestBody body: ByteArray,
        @RequestParam(required = false) filename: String?,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute(
        "strategy-import",
        key,
        mapOf(
            "sha256" to
                app.strategyforge.common.security.Crypto
                    .sha256Hex(body),
            "filename" to filename,
        ),
        HttpStatus.CREATED,
    ) {
        strategies.import(body, filename)
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> {
        val s = strategies.get(parseUuid(id))
        val v = s.currentVersionId?.let { strategies.version(it) }
        val validation = v?.let { strategies.latestValidation(it.id) }
        val explanation = if (v?.validationStatus == "VALIDATED") StrategyExplainer.explain(StrategyDefinition.from(v.content)) else null
        return ETags.ok(mapOf("strategy" to s, "currentVersion" to v, "validation" to validation, "explanation" to explanation, "history" to strategies.statusHistoryOf(s.id)), s.version)
    }

    @PutMapping("/{id}/content")
    fun update(
        @PathVariable id: String,
        @RequestBody req: StrategyContentRequest,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ) = strategies.update(parseUuid(id), req.content, ifMatch)

    @PostMapping("/{id}/clone")
    fun clone(
        @PathVariable id: String,
        @RequestBody(required = false) req: StrategyCloneRequest?,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("strategy-clone:$id", key, req, HttpStatus.CREATED) { strategies.clone(parseUuid(id), req?.name) }

    @PostMapping("/{id}/validate")
    fun validate(
        @PathVariable id: String,
    ) = strategies.revalidate(parseUuid(id))

    @GetMapping("/{id}/versions")
    fun versions(
        @PathVariable id: String,
    ) = strategies.versions(parseUuid(id))

    @GetMapping("/{id}/versions/{n}")
    fun version(
        @PathVariable id: String,
        @PathVariable n: Int,
    ) = strategies.versionByNumber(parseUuid(id), n)

    /** Canonical export; the X-Content-Hash header lets the owner verify integrity (FR-044). */
    @GetMapping("/{id}/export")
    fun export(
        @PathVariable id: String,
        @RequestParam(required = false) version: Int?,
    ): ResponseEntity<String> {
        val sid = parseUuid(id)
        val v = if (version != null) strategies.versionByNumber(sid, version) else strategies.currentVersion(sid) ?: throw Problems.notFound("Strategy version", id)
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Content-Hash", v.contentHash)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"strategy-$id-v${v.versionNumber}.json\"")
            .body(
                com.fasterxml.jackson.databind
                    .ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(v.content),
            )
    }

    @GetMapping("/{id}/explanation")
    fun explanation(
        @PathVariable id: String,
    ): Map<String, String> {
        val v = strategies.currentVersion(parseUuid(id)) ?: throw Problems.notFound("Strategy version", id)
        return mapOf("explanation" to StrategyExplainer.explain(strategies.definition(v.id)), "contentHash" to v.contentHash)
    }

    @PostMapping("/{id}/pause")
    fun pause(
        @PathVariable id: String,
        @RequestBody(required = false) req: StrategyReasonRequest?,
    ) = strategies.pause(parseUuid(id), req?.reason ?: "Paused by owner")

    @PostMapping("/{id}/suspend")
    fun suspend(
        @PathVariable id: String,
        @RequestBody(required = false) req: StrategyReasonRequest?,
    ) = strategies.suspend(parseUuid(id), req?.reason ?: "Suspended by owner")

    @PostMapping("/{id}/archive")
    fun archive(
        @PathVariable id: String,
    ) = strategies.archive(parseUuid(id))
}

@RestController
@RequestMapping("/v1/validations")
@Tag(name = "Strategies")
class ValidationsController(
    private val strategies: StrategyService,
) {
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ) = strategies.validation(parseUuid(id))
}
