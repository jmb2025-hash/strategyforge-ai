package app.strategyforge.common.web

import org.springframework.http.HttpStatus

/**
 * Domain failure mapped to an RFC 7807 problem document. [code] becomes the
 * problem type suffix and is stable for clients.
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val properties: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

object Problems {
    fun badRequest(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = ApiException(HttpStatus.BAD_REQUEST, code, detail, props)

    fun unauthorized(
        code: String,
        detail: String,
    ) = ApiException(HttpStatus.UNAUTHORIZED, code, detail)

    fun forbidden(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = ApiException(HttpStatus.FORBIDDEN, code, detail, props)

    fun notFound(
        entity: String,
        id: Any?,
    ) = ApiException(HttpStatus.NOT_FOUND, "not-found", "$entity $id was not found")

    fun conflict(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = ApiException(HttpStatus.CONFLICT, code, detail, props)

    fun unprocessable(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, detail, props)

    fun preconditionFailed(detail: String) = ApiException(HttpStatus.PRECONDITION_FAILED, "version-mismatch", detail)

    fun preconditionRequired(detail: String) = ApiException(HttpStatus.PRECONDITION_REQUIRED, "if-match-required", detail)

    fun tooMany(
        code: String,
        detail: String,
    ) = ApiException(HttpStatus.TOO_MANY_REQUESTS, code, detail)

    fun unavailable(
        code: String,
        detail: String,
        props: Map<String, Any?> = emptyMap(),
    ) = ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, detail, props)
}
