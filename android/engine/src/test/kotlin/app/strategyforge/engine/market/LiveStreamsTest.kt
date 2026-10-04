package app.strategyforge.engine.market

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Clock
import java.time.Duration

/**
 * D-056 against the real feeds (network; opt-in with SF_LIVE_STREAMS=1): Coinbase Advanced Trade
 * streams BTC-USD without a key, and Alpaca answers a made-up key with its auth error.
 */
@EnabledIfEnvironmentVariable(named = "SF_LIVE_STREAMS", matches = "1")
class LiveStreamsTest {
    private val http = WebSocketQuoteStream.client()

    private fun waitFor(
        seconds: Long,
        what: () -> Boolean,
    ): Boolean {
        val until = System.currentTimeMillis() + seconds * 1000
        while (!what()) {
            if (System.currentTimeMillis() > until) return false
            Thread.sleep(100)
        }
        return true
    }

    @Test
    fun `coinbase streams live BTC-USD ticks`() {
        val s = CoinbaseQuoteStream(http, Clock.systemUTC())
        s.sync(setOf("BTC-USD", "ETH-USD"))
        val got = waitFor(20) { s.latest("BTC-USD", Duration.ofSeconds(30)) != null && s.latest("ETH-USD", Duration.ofSeconds(30)) != null }
        println("Coinbase status: ${s.status()}")
        assertThat(got).`as`("ticks within 20 s").isTrue()
        val t = s.latest("BTC-USD", Duration.ofSeconds(30))!!
        println("BTC-USD: $t")
        assertThat(t.last.signum()).isPositive()
        assertThat(t.bid).isNotNull()
        assertThat(t.ask).isNotNull()
        assertThat(Duration.between(t.exchangeTs, t.receivedAt).abs()).isLessThan(Duration.ofSeconds(30))
        s.sync(emptySet())
    }

    @Test
    fun `alpaca rejects a made-up key with its auth error`() {
        val s = AlpacaQuoteStream(http, Clock.systemUTC(), { "PKFAKEKEY0000000" to "fake-secret-0000000000000000" })
        s.sync(setOf("AAPL"))
        assertThat(waitFor(20) { s.status().state == StreamState.ERROR }).isTrue()
        println("Alpaca status: ${s.status()}")
        assertThat(s.status().error).isEqualTo("Alpaca: auth failed (402)")
    }
}
