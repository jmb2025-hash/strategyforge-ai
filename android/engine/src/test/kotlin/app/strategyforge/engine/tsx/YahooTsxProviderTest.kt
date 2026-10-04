package app.strategyforge.engine.tsx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class YahooTsxProviderTest {
    private val yahoo = YahooTsxProvider()

    @Test
    fun `parses closes, adjusted closes and dividends in Toronto dates`() {
        // 2026-09-14 and 2026-09-15 at 09:30 Toronto (13:30 UTC); the second close is missing.
        val body =
            """
            {"chart":{"result":[{"timestamp":[1789392600,1789479000,1789565400],
              "events":{"dividends":{"1789479000":{"amount":0.42,"date":1789479000}}},
              "indicators":{"quote":[{"close":[101.5,null,103.25]}],"adjclose":[{"adjclose":[100.0,null,102.0]}]}}],"error":null}}
            """.trimIndent()
        val r = yahoo.parse(body) as TsxFetch.Ok
        assertThat(r.bars.map { it.day }).containsExactly(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-16"))
        assertThat(r.bars.first().close).isEqualTo(101.5)
        assertThat(r.bars.first().adj).isEqualTo(100.0)
        assertThat(r.dividends).containsEntry(LocalDate.parse("2026-09-15"), 0.42)
    }

    @Test
    fun `reports a delisted symbol as not found`() {
        val r = yahoo.parse("""{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}""")
        assertThat(r).isEqualTo(TsxFetch.Failed("No data found, symbol may be delisted", notFound = true))
        assertThat(yahoo.parse("not json")).isInstanceOf(TsxFetch.Failed::class.java)
    }

    @Test
    fun `reads the current price from the chart meta`() {
        val q = yahoo.parseLatest("""{"chart":{"result":[{"meta":{"currency":"CAD","regularMarketPrice":278.91,"regularMarketTime":1790971200,"previousClose":277.86}}]}}""")!!
        assertThat(q.price).isEqualTo(278.91)
        assertThat(q.at.epochSecond).isEqualTo(1790971200)
        assertThat(q.previousClose).isEqualTo(277.86)
        assertThat(yahoo.parseLatest("""{"chart":{"result":null}}""")).isNull()
    }
}
