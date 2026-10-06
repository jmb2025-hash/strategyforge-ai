package app.strategyforge.android.core

import app.strategyforge.android.core.format.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** D-071 a time the engine stores as "never" (1970-01-01) is not shown as Dec 31, 1969. */
class NeverDateTest {
    private val fmt = Formatters(ZoneId.of("America/Halifax"))

    @Test
    fun `the epoch reads as never and real times are unchanged`() {
        assertEquals("—", fmt.dateTime("1970-01-01T00:00:00Z"))
        assertEquals("Never", fmt.dateTime("1970-01-01T00:00:00Z", never = "Never"))
        assertEquals("Never", fmt.dateTime(null, never = "Never"))
        assertTrue(fmt.dateTime("2026-10-06T03:23:43Z").contains("2026"))
    }
}
