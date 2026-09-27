package app.strategyforge.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Assigns a correlation ID to every request; echoed in responses, logs and audit (NFR-004, NFR-010). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val supplied = request.getHeader(HEADER)
        val id = if (supplied != null && VALID.matches(supplied)) supplied else UUID.randomUUID().toString()
        MDC.put(MDC_KEY, id)
        response.setHeader(HEADER, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"
        private val VALID = Regex("^[A-Za-z0-9-]{8,64}$")

        fun current(): String = MDC.get(MDC_KEY) ?: "system-" + UUID.randomUUID()

        /** Runs [block] with a correlation ID for scheduler/background work. */
        fun <T> withCorrelation(
            prefix: String,
            block: () -> T,
        ): T {
            val previous = MDC.get(MDC_KEY)
            MDC.put(MDC_KEY, "$prefix-" + UUID.randomUUID())
            try {
                return block()
            } finally {
                if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
            }
        }
    }
}
