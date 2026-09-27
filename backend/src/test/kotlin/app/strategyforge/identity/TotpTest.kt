package app.strategyforge.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TotpTest {
    @Test
    fun `FR-002 TOTP matches RFC 6238 SHA1 test vectors`() {
        // RFC 6238 Appendix B secret "12345678901234567890" (ASCII) with 8 digits; we use the last 6 digits.
        val secret = Totp.base32("12345678901234567890".toByteArray())
        val vectors = mapOf(59L to "94287082", 1111111109L to "07081804", 1234567890L to "89005924", 2000000000L to "69279037")
        vectors.forEach { (t, expected8) ->
            assertThat(Totp.code(secret, Totp.step(Instant.ofEpochSecond(t)))).isEqualTo(expected8.takeLast(6))
        }
    }

    @Test
    fun `FR-002 TOTP verification accepts adjacent steps only`() {
        val s = Totp.newSecret()
        val now = Instant.ofEpochSecond(1_800_000_000)
        val step = Totp.step(now)
        assertThat(Totp.verify(s, Totp.code(s, step - 1), now)).isEqualTo(step - 1)
        assertThat(Totp.verify(s, Totp.code(s, step + 2), now)).isNull()
        assertThat(Totp.verify(s, "abc", now)).isNull()
        assertThat(Totp.base32Decode(Totp.base32(byteArrayOf(1, 2, 3, 4, 5)))).containsExactly(1, 2, 3, 4, 5)
    }
}
