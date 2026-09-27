package app.strategyforge.portfolio

import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.parseUuid
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CloneRequest(
    val name: String,
)

data class ResetRequest(
    val confirm: String?,
)

data class ShortingRequest(
    val enabled: Boolean,
)

@RestController
@RequestMapping("/v1/portfolios")
@Tag(name = "Portfolio")
class PortfoliosController(
    private val portfolios: PortfolioService,
    private val reconciliation: ReconciliationService,
    private val idempotency: IdempotencyService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
    ) = portfolios.list(includeArchived)

    @PostMapping
    fun create(
        @RequestBody req: PortfolioCreate,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("portfolio-create", key, req, HttpStatus.CREATED) { portfolios.create(req) }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): ResponseEntity<Portfolio> = portfolios.get(parseUuid(id)).let { ETags.ok(it, it.version) }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody req: PortfolioUpdate,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ) = portfolios.update(parseUuid(id), req, ifMatch).let { ETags.ok(it, it.version) }

    @GetMapping("/{id}/summary")
    fun summary(
        @PathVariable id: String,
    ) = portfolios.summary(parseUuid(id))

    @GetMapping("/{id}/equity-history")
    fun equity(
        @PathVariable id: String,
        @RequestParam(defaultValue = "500") limit: Int,
    ) = portfolios.equityHistory(parseUuid(id), limit.coerceIn(1, 5000))

    @PostMapping("/{id}/archive")
    fun archive(
        @PathVariable id: String,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("portfolio-archive:$id", key, null) { portfolios.archive(parseUuid(id)) }

    @PostMapping("/{id}/clone")
    fun clone(
        @PathVariable id: String,
        @RequestBody req: CloneRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("portfolio-clone:$id", key, req, HttpStatus.CREATED) { portfolios.clone(parseUuid(id), req.name) }

    @PostMapping("/{id}/reset")
    fun reset(
        @PathVariable id: String,
        @RequestBody req: ResetRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("portfolio-reset:$id", key, req, HttpStatus.CREATED) { portfolios.reset(parseUuid(id), req.confirm) }

    @PutMapping("/{id}/shorting")
    fun shorting(
        @PathVariable id: String,
        @RequestBody req: ShortingRequest,
    ) = portfolios.setShorting(parseUuid(id), req.enabled)

    @PostMapping("/{id}/reconciliation")
    fun reconcile(
        @PathVariable id: String,
    ) = reconciliation.run(parseUuid(id))

    @GetMapping("/{id}/reconciliation")
    fun reconciliations(
        @PathVariable id: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ) = reconciliation.runs(parseUuid(id), limit.coerceIn(1, 200))
}

@RestController
@RequestMapping("/v1/ledger")
@Tag(name = "Portfolio")
class LedgerController(
    private val ledger: LedgerService,
    private val portfolios: PortfolioService,
) {
    @GetMapping
    fun journals(
        @RequestParam portfolioId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = portfolios.get(parseUuid(portfolioId)).let { ledger.journals(it.id, cursor, limit) }

    @GetMapping("/balances")
    fun balances(
        @RequestParam portfolioId: String,
    ) = portfolios.get(parseUuid(portfolioId)).let { ledger.balances(it.id) }
}

@RestController
@RequestMapping("/v1/positions")
@Tag(name = "Portfolio")
class PositionsController(
    private val portfolios: PortfolioService,
    private val lots: LotService,
) {
    @GetMapping
    fun positions(
        @RequestParam portfolioId: String,
    ) = portfolios.get(parseUuid(portfolioId)).let { portfolios.positionViews(it) }

    @GetMapping("/lots")
    fun openLots(
        @RequestParam portfolioId: String,
    ) = lots.openLots(parseUuid(portfolioId))

    @GetMapping("/closed")
    fun closed(
        @RequestParam portfolioId: String,
        @RequestParam(defaultValue = "200") limit: Int,
    ) = lots.closedLots(parseUuid(portfolioId), limit.coerceIn(1, 1000))
}
