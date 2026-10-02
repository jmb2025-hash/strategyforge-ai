package app.strategyforge.android.core

import app.strategyforge.android.core.data.StrategyText
import org.junit.Assert.assertEquals
import org.junit.Test

/** D-041 a reply pasted from an AI chat is imported without hand-editing. */
class StrategyTextTest {
    @Test
    fun `the JSON inside a fenced block is used and the chat around it is ignored`() {
        val reply = "Here is your strategy:\n\n```json\n{\"schemaVersion\": \"1.0\", \"metadata\": {\"name\": \"X\"}}\n```\n\nLet me know if you want changes."
        assertEquals("{\"schemaVersion\": \"1.0\", \"metadata\": {\"name\": \"X\"}}", StrategyText.extract(reply))
    }

    @Test
    fun `without a fence the outermost object is used, and plain JSON is unchanged`() {
        assertEquals("{\"a\": {\"b\": 1}}", StrategyText.extract("Sure! {\"a\": {\"b\": 1}} Hope that helps."))
        assertEquals("{\"a\": 1}", StrategyText.extract("  {\"a\": 1}  "))
        assertEquals("not json", StrategyText.extract(" not json "))
    }

    @Test
    fun `the text around the strategy is kept as the AI's notes`() {
        val reply = "Intro\n```json\n{\"a\": 1}\n```\nRULE READBACK\n- Buy when RSI < 30"
        assertEquals("Intro\n\nRULE READBACK\n- Buy when RSI < 30", StrategyText.notes(reply))
        assertEquals("STILL MISSING: None", StrategyText.notes("{\"a\": 1}\nSTILL MISSING: None"))
        assertEquals(null, StrategyText.notes("```json\n{\"a\": 1}\n```"))
    }
}
