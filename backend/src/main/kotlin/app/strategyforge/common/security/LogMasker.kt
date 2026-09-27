package app.strategyforge.common.security

import com.fasterxml.jackson.core.JsonStreamContext
import net.logstash.logback.mask.ValueMasker

/** Logback JSON value masker delegating to [SecretRedactor] (NFR-010). */
class LogMasker : ValueMasker {
    override fun mask(
        context: JsonStreamContext,
        value: Any?,
    ): Any? {
        val field = context.currentName
        if (field != null && SecretRedactor.isSensitiveKey(field) && field != "logger_name") return SecretRedactor.MASK
        if (value is CharSequence) {
            val s = value.toString()
            val r = SecretRedactor.redact(s)
            if (r != s) return r
        }
        return null
    }
}
