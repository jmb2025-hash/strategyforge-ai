package app.strategyforge.engine.research

import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** D-043 the readback and research notes an outside AI writes around the strategy are kept and split into sections. */
class ImportNotesTest {
    private val reply =
        """
        Done. I researched the gaps before writing this.

        **RULE READBACK**
        - Long when price sweeps the 42-bar low and closes back above it, with volume over 1.5x average.
        - Stop 3% below entry; take half off at +3% and move the stop to entry.
          The rest exits at the range high.
        - Risk 1% of equity per trade; no new trades after 3 losing trades in a day.

        ## Further research:
        1. Stop placement: the research only said "below the swing" -> educator material puts it under the SFP wick, about 2-3% on 4h (source: Chart Champions course notes).
        2. Daily losing-trade limit: missing -> they stop after three losses a day (source: interview transcript).

        STILL MISSING: None

        Let me know if you want a stock version.
        """.trimIndent()

    @Test
    fun `the three sections are read whatever heading style the AI used`() {
        val n = ImportNotes.parse(reply)!!
        assertThat(n.readback).hasSize(3)
        assertThat(n.readback[1]).isEqualTo("Stop 3% below entry; take half off at +3% and move the stop to entry. The rest exits at the range high.")
        assertThat(n.furtherResearch).hasSize(2)
        assertThat(n.furtherResearch[0]).startsWith("Stop placement:").contains("source: Chart Champions")
        assertThat(n.stillMissing).`as`("\"None\" means nothing is missing").isEmpty()
        assertThat(n.other).contains("Done. I researched").contains("Let me know")
    }

    @Test
    fun `missing points are listed, prose is not mistaken for a heading and odd characters are removed`() {
        val n =
            ImportNotes.parse(
                "Further research shows the method is popular.\u0007\n\nSTILL MISSING\n- Short entry: never described - no public material - left out\n- Max holding time: not stated - used 42 bars",
            )!!
        assertThat(n.stillMissing).containsExactly("Short entry: never described - no public material - left out", "Max holding time: not stated - used 42 bars")
        assertThat(n.furtherResearch).isEmpty()
        assertThat(n.other).isEqualTo("Further research shows the method is popular.")
        assertThat(ImportNotes.parse("   ")).isNull()
        assertThat(ImportNotes.clean("x".repeat(40_000))!!.length).isEqualTo(ImportNotes.MAX_CHARS)
    }

    @Test
    fun `an imported strategy keeps its notes and one without notes has none`() {
        val e = TestEngine.create()
        val json = javaClass.getResource("/strategies/valid_crypto_rsi.json")?.readBytes() ?: error("fixture")
        val withNotes = e.strategies.import(json, "a.json", reply)
        assertThat(e.strategies.importNotes(withNotes.strategy.id)!!.furtherResearch).hasSize(2)
        val without = e.strategies.import(json, "b.json")
        assertThat(e.strategies.importNotes(without.strategy.id)).isNull()
    }
}
