package app.strategyforge.android.core

import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.state.FieldError
import app.strategyforge.android.core.state.OrderForm
import app.strategyforge.android.core.state.toFailure
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-065 problems found on the order screen belong to a field, so they are shown there. */
class OrderFormTest {
    @Test
    fun `each order form problem is keyed by its field and the first is scrolled to`() {
        assertTrue(OrderForm.problems("AAPL", "1", "").isEmpty())
        assertTrue(OrderForm.problems("AAPL", "0.5", "190.25").isEmpty())
        val p = OrderForm.problems("", "abc", "-1")
        assertEquals(setOf(OrderForm.SYMBOL, OrderForm.QUANTITY, OrderForm.LIMIT), p.keys)
        assertEquals(OrderForm.SYMBOL, OrderForm.first(p))
        assertEquals(OrderForm.QUANTITY, OrderForm.first(OrderForm.problems("AAPL", "0", "x")))
    }

    @Test
    fun `D-066 ordering by dollar amount checks the amount field and accepts dollar signs and commas`() {
        assertTrue(OrderForm.problems("BTC-USD", "$1,250.50", "", byAmount = true).isEmpty())
        assertEquals(setOf(OrderForm.AMOUNT), OrderForm.problems("BTC-USD", "0", "", byAmount = true).keys)
        assertEquals("1250.50", OrderForm.cleanAmount(" $1,250.50 "))
        assertEquals(OrderForm.AMOUNT, OrderForm.first(OrderForm.problems("BTC-USD", "", "x", byAmount = true)))
    }

    @Test
    fun `an engine problem naming a field becomes a failure for that field`() {
        val e = ApiError.Http(404, "unknown-symbol", "Not Found", "ZZZZ isn't a symbol", buildJsonObject { put("field", JsonPrimitive("symbol")) })
        assertEquals("symbol", e.toFailure().field)
        assertEquals("ZZZZ isn't a symbol", e.toFailure().message)
        assertEquals("quantity", FieldError("quantity", "Enter a quantity").toFailure().field)
        assertNull(IllegalStateException("x").toFailure().field)
    }
}
