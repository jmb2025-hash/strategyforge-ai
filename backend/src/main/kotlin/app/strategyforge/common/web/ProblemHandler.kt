package app.strategyforge.common.web

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI

const val PROBLEM_BASE = "https://strategyforge.app/problems/"

fun problem(
    status: HttpStatus,
    code: String,
    detail: String,
    path: String?,
    props: Map<String, Any?> = emptyMap(),
): ProblemDetail {
    val pd = ProblemDetail.forStatusAndDetail(status, detail)
    pd.type = URI.create(PROBLEM_BASE + code)
    pd.title = code.replace('-', ' ').replaceFirstChar { it.uppercase() }
    if (path != null) pd.instance = URI.create(path)
    pd.setProperty("code", code)
    pd.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
    props.forEach { (k, v) -> pd.setProperty(k, v) }
    return pd
}

/** Maps all failures to RFC 7807 problem details without leaking internals. */
@RestControllerAdvice
class ProblemHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(
        e: ApiException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(e.status).body(problem(e.status, e.code, e.message, req.requestURI, e.properties))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(
        e: MethodArgumentNotValidException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val errors = e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) }
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed", req.requestURI, mapOf("errors" to errors)))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(
        e: HttpMessageNotReadableException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val cause = e.mostSpecificCause
        val detail =
            when (cause) {
                is UnrecognizedPropertyException -> "Unknown field '${cause.propertyName}' is not accepted"
                else -> "Malformed or invalid JSON request body"
            }
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "malformed-request", detail, req.requestURI))
    }

    @ExceptionHandler(MissingRequestHeaderException::class, MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun missing(
        e: Exception,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "invalid-request", e.message ?: "Invalid request", req.requestURI))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(
        e: MaxUploadSizeExceededException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Upload exceeds the permitted size", req.requestURI))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(
        e: HttpRequestMethodNotSupportedException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(problem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed", req.requestURI))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun media(
        e: HttpMediaTypeNotSupportedException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type", req.requestURI))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(
        e: NoResourceFoundException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", req.requestURI))

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(
        e: AccessDeniedException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(HttpStatus.FORBIDDEN, "permission-denied", "Permission denied", req.requestURI))

    @ExceptionHandler(AuthenticationException::class)
    fun unauth(
        e: AuthenticationException,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required", req.requestURI))

    @ExceptionHandler(Exception::class)
    fun unexpected(
        e: Exception,
        req: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        log.error("Unhandled failure on {} {}", req.method, req.requestURI, e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "An internal error occurred. Use the correlation ID to find details in server logs.", req.requestURI))
    }
}
