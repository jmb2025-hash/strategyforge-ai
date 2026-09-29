package app.strategyforge.engine.common

/**
 * Removes secrets from free text and structured maps before they reach logs,
 * audit details, exports or crash reports (FR-031, NFR-010, security section).
 */
object SecretRedactor {
    const val MASK = "[REDACTED]"

    private val SENSITIVE_KEY = Regex("(?i)(password|passphrase|secret|token|api[-_]?key|apikey|authorization|credential|private[-_]?key|recovery[-_]?code|totp|otp|cookie|session[-_]?key|master[-_]?key|signing[-_]?key)")

    private val VALUE_PATTERNS =
        listOf(
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
            Regex("sk-ant-[A-Za-z0-9_-]{8,}"),
            Regex("sk-or-[A-Za-z0-9_-]{8,}"),
            Regex("sk-(proj-)?[A-Za-z0-9_-]{16,}"),
            Regex("AIza[0-9A-Za-z_-]{20,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Regex("(?i)(apikey|api_key|token|password|secret)=([^&\\s\"]+)"),
            Regex("(?i)\"(password|apiKey|api_key|secret|token|privateKey|private_key)\"\\s*:\\s*\"[^\"]*\""),
        )

    fun isSensitiveKey(key: String): Boolean = SENSITIVE_KEY.containsMatchIn(key)

    fun redact(text: String?): String? {
        if (text == null) return null
        var out: String = text
        VALUE_PATTERNS.forEach { p ->
            out =
                p.replace(out) { m ->
                    when {
                        m.groupValues.size > 2 && m.value.contains('=') -> "${m.groupValues[1]}=$MASK"
                        m.value.startsWith("\"") -> "\"${m.groupValues[1]}\":\"$MASK\""
                        else -> MASK
                    }
                }
        }
        return out
    }

    fun redactMap(map: Map<String, Any?>): Map<String, Any?> =
        map.mapValues { (k, v) ->
            when {
                isSensitiveKey(k) -> if (v == null) null else MASK
                v is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    redactMap(v as Map<String, Any?>)
                }
                v is String -> redact(v)
                v is List<*> -> v.map { e -> if (e is String) redact(e) else e }
                else -> v
            }
        }
}
