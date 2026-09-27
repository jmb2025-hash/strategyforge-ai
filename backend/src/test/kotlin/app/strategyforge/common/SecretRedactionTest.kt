package app.strategyforge.common

import app.strategyforge.common.security.LogMasker
import app.strategyforge.common.security.SecretRedactor
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import net.logstash.logback.encoder.LogstashEncoder
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecretRedactionTest {
    @Test
    fun `NFR-010 secrets are redacted from text and maps`() {
        val text = "calling with Authorization: Bearer abcdefghijklmnop.qrstu and key sk-ant-api03-SECRETSECRET and AIzaSyA1234567890abcdefghijk url?apikey=XYZ123"
        val r = SecretRedactor.redact(text)!!
        assertThat(r).doesNotContain("abcdefghijklmnop", "SECRETSECRET", "AIzaSyA1234567890", "XYZ123")
        val m = SecretRedactor.redactMap(mapOf("password" to "hunter2", "nested" to mapOf("apiKey" to "k"), "note" to "ok"))
        assertThat(m["password"]).isEqualTo("[REDACTED]")
        assertThat((m["nested"] as Map<*, *>)["apiKey"]).isEqualTo("[REDACTED]")
        assertThat(m["note"]).isEqualTo("ok")
    }

    @Test
    fun `NFR-010 JSON log encoder masks secrets in messages and correlates`() {
        val ctx = LoggerContext()
        val encoder = LogstashEncoder()
        encoder.context = ctx
        val decorator = MaskingJsonGeneratorDecorator()
        decorator.addValueMasker(LogMasker())
        decorator.start()
        encoder.jsonGeneratorDecorator = decorator
        encoder.addIncludeMdcKeyName("correlationId")
        encoder.start()
        val logger = ctx.getLogger("test")
        val event = LoggingEvent("fqcn", logger, Level.INFO, "provider call with sk-proj-ABCDEFGHIJKLMNOPQRSTUV failed", null, null)
        event.setMDCPropertyMap(mapOf("correlationId" to "corr-12345678"))
        val json = String(encoder.encode(event))
        assertThat(json)
            .doesNotContain("ABCDEFGHIJKLMNOPQRSTUV")
            .contains("[REDACTED]")
            .contains("\"level\":\"INFO\"")
            .contains("corr-12345678")
    }
}
