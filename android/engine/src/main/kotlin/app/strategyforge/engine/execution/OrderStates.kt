package app.strategyforge.engine.execution

enum class OrderStatus(
    val open: Boolean,
    val terminal: Boolean,
) {
    CREATED(true, false),
    VALIDATED(true, false),
    REJECTED(false, true),
    PENDING(true, false),
    PARTIALLY_FILLED(true, false),
    FILLED(false, true),
    CANCELLED(false, true),
    EXPIRED(false, true),
    FAILED(false, true),
}

/** Paper order lifecycle (section 8). Any transition not listed is refused. */
object OrderStateMachine {
    private val allowed =
        mapOf(
            OrderStatus.CREATED to setOf(OrderStatus.VALIDATED, OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
            OrderStatus.VALIDATED to setOf(OrderStatus.PENDING, OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
            OrderStatus.PENDING to setOf(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
            OrderStatus.PARTIALLY_FILLED to setOf(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED),
        )

    fun canTransition(
        from: OrderStatus,
        to: OrderStatus,
    ): Boolean = allowed[from]?.contains(to) == true

    fun check(
        from: OrderStatus,
        to: OrderStatus,
    ) {
        check(canTransition(from, to)) { "Illegal order transition $from -> $to" }
    }
}
