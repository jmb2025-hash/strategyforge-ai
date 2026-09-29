package app.strategyforge.engine.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Shared JSON settings for persisted engine data. Unknown keys are ignored so older rows keep loading. */
val EngineJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/** BigDecimal as a plain decimal string (never a floating-point number, NFR-002). */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) = encoder.encodeString(value.toPlainString())

    /** Accepts a JSON string or a JSON number (the server wrote some amounts as numbers). */
    override fun deserialize(decoder: Decoder): BigDecimal = if (decoder is JsonDecoder) BigDecimal(decoder.decodeJsonElement().jsonPrimitive.content) else BigDecimal(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: UUID,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Converts loosely typed values (audit details, snapshots) into JSON without reflection. */
fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is BigDecimal -> JsonPrimitive(this.toPlainString())
        is Number -> JsonPrimitive(this.toString())
        is Instant, is UUID, is LocalDate -> JsonPrimitive(this.toString())
        is Enum<*> -> JsonPrimitive(this.name)
        is Map<*, *> -> JsonObject(this.entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
        is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
        is Array<*> -> JsonArray(this.map { it.toJsonElement() })
        else -> JsonPrimitive(this.toString())
    }

/** Canonical JSON: object keys sorted recursively, no insignificant whitespace (audit hash input). */
fun JsonElement.canonical(): String =
    when (this) {
        is JsonObject -> keys.sorted().joinToString(",", "{", "}") { k -> JsonPrimitive(k).toString() + ":" + getValue(k).canonical() }
        is JsonArray -> joinToString(",", "[", "]") { it.canonical() }
        else -> toString()
    }
