package app.strategyforge.engine.common

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** Hashing and canonical JSON that work on every supported Android version (minSdk 29). */
object Hashing {
    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = HEX[(b.toInt() shr 4) and 0xF]
            out[i * 2 + 1] = HEX[b.toInt() and 0xF]
        }
        return String(out)
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))
}

/** Canonical JSON of a Jackson tree (sorted keys, plain decimals), identical to the Version 1 server's. */
object JacksonCanonical {
    val mapper: ObjectMapper = ObjectMapper()

    fun canonical(node: JsonNode): String = StringBuilder().also { write(node, it) }.toString()

    private fun write(
        n: JsonNode,
        sb: StringBuilder,
    ) {
        when {
            n.isObject -> {
                sb.append('{')
                n.fieldNames().asSequence().sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    sb.append(mapper.writeValueAsString(k)).append(':')
                    write(n.get(k), sb)
                }
                sb.append('}')
            }
            n.isArray -> {
                sb.append('[')
                n.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    write(e, sb)
                }
                sb.append(']')
            }
            n.isNumber -> {
                val bd = n.decimalValue().stripTrailingZeros()
                sb.append(if (bd.signum() == 0) "0" else bd.toPlainString())
            }
            else -> sb.append(mapper.writeValueAsString(n))
        }
    }
}
