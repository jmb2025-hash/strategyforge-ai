package app.strategyforge.identity

import app.strategyforge.common.security.Crypto
import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30-second steps) with RFC 4648 base32 secrets. */
object Totp {
    private const val DIGITS = 6
    private const val STEP_SECONDS = 30L
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun newSecret(): String = base32(Crypto.randomBytes(20))

    fun step(at: Instant): Long = at.epochSecond / STEP_SECONDS

    fun code(
        secretBase32: String,
        step: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(base32Decode(secretBase32), "HmacSHA1"))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = h[h.size - 1].toInt() and 0x0f
        val bin =
            ((h[offset].toInt() and 0x7f) shl 24) or ((h[offset + 1].toInt() and 0xff) shl 16) or
                ((h[offset + 2].toInt() and 0xff) shl 8) or (h[offset + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** Returns the matching step within +/-1 step of [at], or null. Callers reject reuse of a step. */
    fun verify(
        secretBase32: String,
        code: String,
        at: Instant,
    ): Long? {
        if (!Regex("^\\d{6}$").matches(code)) return null
        val now = step(at)
        return (now - 1..now + 1).firstOrNull { Crypto.constantTimeEquals(code(secretBase32, it), code) }
    }

    fun uri(
        secretBase32: String,
        account: String,
    ): String = "otpauth://totp/StrategyForge:${java.net.URLEncoder.encode(account, Charsets.UTF_8)}?secret=$secretBase32&issuer=StrategyForge&algorithm=SHA1&digits=6&period=30"

    fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun base32Decode(s: String): ByteArray {
        val clean = s.uppercase().filter { it != '=' && it != ' ' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { "invalid base32" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
