package app.strategyforge.engine.common

/**
 * Domain failure with a stable [code] (the same codes the Version 1 API used), so the UI can
 * explain it and tests can assert on it. [status] keeps the HTTP-like class for familiarity.
 */
open class EngineException(
    val status: Int,
    val code: String,
    override val message: String,
    val properties: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

object Problems {
    fun badRequest(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = EngineException(400, code, detail, props)

    fun forbidden(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = EngineException(403, code, detail, props)

    fun notFound(
        entity: String,
        id: Any?,
    ) = EngineException(404, "not-found", "$entity $id was not found")

    fun conflict(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = EngineException(409, code, detail, props)

    fun unprocessable(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = EngineException(422, code, detail, props)

    fun preconditionFailed(detail: String) = EngineException(412, "version-mismatch", detail)

    fun tooMany(
        code: String,
        detail: String,
    ) = EngineException(429, code, detail)

    fun unavailable(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = EngineException(503, code, detail, props)

    /** The owner must confirm with the device lock (biometric or PIN) first; the UI asks, then retries. */
    fun recentAuthRequired(operation: String) = EngineException(403, "recent-authentication-required", "Confirm it's you to $operation", mapOf("operation" to operation))
}
