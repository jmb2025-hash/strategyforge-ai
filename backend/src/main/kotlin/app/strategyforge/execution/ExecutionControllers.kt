package app.strategyforge.execution

import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.parseUuid
import app.strategyforge.risk.OrderSource
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CancelRequest(
    val reason: String? = null,
)

@RestController
@RequestMapping("/v1/orders")
@Tag(name = "Execution")
class OrdersController(
    private val orders: OrderService,
    private val engine: ExecutionEngine,
    private val idempotency: IdempotencyService,
) {
    /** Manual paper order. Idempotency-Key is mandatory so retries never create duplicates (NFR-007). */
    @PostMapping
    fun create(
        @RequestBody req: OrderRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("order-create", key, req, HttpStatus.CREATED) { orders.create(req, OrderSource.MANUAL, OrderLinks()) }

    @GetMapping
    fun list(
        @RequestParam(required = false) portfolioId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = orders.list(portfolioId?.let { parseUuid(it) }, status, cursor, limit)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): Map<String, Any?> {
        val o = orders.get(parseUuid(id))
        return mapOf("order" to o, "history" to orders.statusHistory(o.id), "executions" to engine.executions(null, o.id, 1000))
    }

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: String,
        @RequestBody(required = false) req: CancelRequest?,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("order-cancel:$id", key, req) { orders.cancel(parseUuid(id), req?.reason ?: "Cancelled by owner") }
}

@RestController
@RequestMapping("/v1/executions")
@Tag(name = "Execution")
class ExecutionsController(
    private val engine: ExecutionEngine,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) portfolioId: String?,
        @RequestParam(required = false) orderId: String?,
        @RequestParam(defaultValue = "200") limit: Int,
    ) = engine.executions(portfolioId?.let { parseUuid(it) }, orderId?.let { parseUuid(it) }, limit.coerceIn(1, 1000))
}
