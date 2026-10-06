package app.strategyforge.android.core.state

/**
 * The order screen's own checks before anything is sent (D-065): each problem is keyed by the field
 * it belongs to, so the screen can show it under that field and scroll there.
 */
object OrderForm {
    const val SYMBOL = "symbol"
    const val QUANTITY = "quantity"
    const val LIMIT = "limitPrice"

    /** The dollar amount field (D-066), used instead of [QUANTITY] when ordering by value. */
    const val AMOUNT = "amount"

    /** [quantity] is a dollar amount when [byAmount] is true. */
    fun problems(
        symbol: String,
        quantity: String,
        limit: String,
        byAmount: Boolean = false,
    ): Map<String, String> =
        buildMap {
            if (symbol.isBlank()) put(SYMBOL, "Type a company, fund or coin name and pick it from the list")
            val q =
                quantity
                    .trim()
                    .removePrefix("$")
                    .replace(",", "")
                    .toBigDecimalOrNull()
            if (byAmount) {
                if (q == null || q.signum() <= 0) put(AMOUNT, "Enter a dollar amount above zero")
            } else if (q == null || q.signum() <= 0) {
                put(QUANTITY, "Enter a quantity above zero")
            }
            if (limit.isNotBlank()) {
                val l = limit.trim().toBigDecimalOrNull()
                if (l == null || l.signum() <= 0) put(LIMIT, "Enter a price above zero, or leave it blank for a market order")
            }
        }

    /** Form fields in screen order, so the first one with a problem is the one scrolled to. */
    val ORDER = listOf(SYMBOL, AMOUNT, QUANTITY, LIMIT)

    /** "$1,250.50" → "1250.50"; the amount field accepts what people type. */
    fun cleanAmount(text: String): String =
        text
            .trim()
            .removePrefix("$")
            .replace(",", "")
            .trim()

    fun first(problems: Map<String, String>): String? = ORDER.firstOrNull { it in problems } ?: problems.keys.firstOrNull()
}
