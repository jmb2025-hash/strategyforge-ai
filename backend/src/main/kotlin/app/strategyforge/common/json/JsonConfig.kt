package app.strategyforge.common.json

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * API JSON rules: ISO-8601 timestamps, decimals serialized as strings (clients never
 * see JSON floating point for financial values), unknown request fields rejected.
 */
@Configuration
class JsonConfig {
    @Bean
    fun strategyForgeJacksonCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { b ->
            b.modulesToInstall(JavaTimeModule(), KotlinModule.Builder().build(), decimalModule())
            b.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            b.featuresToEnable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            )
            b.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        }

    companion object {
        fun decimalModule(): SimpleModule = SimpleModule("sf-decimals").addSerializer(BigDecimal::class.java, PlainDecimalSerializer())

        /** A strict standalone mapper for non-HTTP use (strategy files, audit details, exports). */
        fun strictMapper(): ObjectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .registerModule(KotlinModule.Builder().build())
                .registerModule(decimalModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .also { it.factory.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()) }

        /** Canonical JSON: sorted keys, no insignificant whitespace, normalized decimals. */
        fun canonical(
            mapper: ObjectMapper,
            node: JsonNode,
        ): String = StringBuilder().also { writeCanonical(mapper, node, it) }.toString()

        private fun writeCanonical(
            mapper: ObjectMapper,
            n: JsonNode,
            sb: StringBuilder,
        ) {
            when {
                n.isObject -> {
                    sb.append('{')
                    n.fieldNames().asSequence().sorted().forEachIndexed { i, k ->
                        if (i > 0) sb.append(',')
                        sb.append(mapper.writeValueAsString(k)).append(':')
                        writeCanonical(mapper, n.get(k), sb)
                    }
                    sb.append('}')
                }
                n.isArray -> {
                    sb.append('[')
                    n.forEachIndexed { i, e ->
                        if (i > 0) sb.append(',')
                        writeCanonical(mapper, e, sb)
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

        fun objectNode(mapper: ObjectMapper): ObjectNode = mapper.createObjectNode()
    }
}

class PlainDecimalSerializer : JsonSerializer<BigDecimal>() {
    override fun serialize(
        value: BigDecimal,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        gen.writeString(value.toPlainString())
    }
}
