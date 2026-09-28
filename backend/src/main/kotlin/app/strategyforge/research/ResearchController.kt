package app.strategyforge.research

import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.parseUuid
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/research")
@Tag(name = "Research")
class ResearchController(
    private val research: ResearchService,
    private val budgets: AiBudgetService,
    private val idempotency: IdempotencyService,
) {
    @GetMapping
    fun list() = research.list()

    @PostMapping
    fun create(
        @RequestBody req: ResearchCreate,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("research-create", key, req, HttpStatus.CREATED) { research.create(req) }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ) = research.detail(parseUuid(id))

    /** Starts a provider call (202); poll the session for the result. */
    @PostMapping("/{id}/run")
    fun run(
        @PathVariable id: String,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("research-run:$id", key, null, HttpStatus.ACCEPTED) { research.run(parseUuid(id)) }

    @PostMapping("/{id}/edits")
    fun edit(
        @PathVariable id: String,
        @RequestBody req: EditRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("research-edit:$id", key, req, HttpStatus.CREATED) { research.edit(parseUuid(id), req) }

    @PostMapping("/{id}/review")
    fun review(
        @PathVariable id: String,
        @RequestBody req: ReviewRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("research-review:$id", key, req) { research.review(parseUuid(id), req) }

    @PostMapping("/{id}/compile")
    fun compile(
        @PathVariable id: String,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("research-compile:$id", key, null, HttpStatus.ACCEPTED) { research.compile(parseUuid(id)) }

    /** AI provenance of a compiled strategy version (FR-036). */
    @GetMapping("/provenance/versions/{versionId}")
    fun provenance(
        @PathVariable versionId: String,
    ) = research.provenance(parseUuid(versionId))

    @GetMapping("/budget")
    fun budget(): ResponseEntity<AiBudget> = budgets.get().let { ETags.ok(it, it.version) }

    @PutMapping("/budget")
    fun updateBudget(
        @RequestBody req: AiBudgetUpdate,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AiBudget> = budgets.update(req, ifMatch).let { ETags.ok(it, it.version) }
}
