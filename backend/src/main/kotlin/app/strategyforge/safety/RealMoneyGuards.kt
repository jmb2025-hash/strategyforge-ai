package app.strategyforge.safety

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.web.problem
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Startup guard on bound configuration (covers application.yml and system properties too). */
@Component
class RealMoneyStartupGuard(
    private val props: StrategyForgeProperties,
) {
    @PostConstruct
    fun check() = RealMoneyPolicy.assertFlagSafe(props.realMoneyTradingEnabled)
}

/** Rejects brokerage-shaped routes before authentication and records the attempt (FR-113). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RealMoneyRouteFilter(
    private val audit: AuditService,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (RealMoneyPolicy.PROHIBITED_ROUTE.matches(path)) {
            audit.recordIndependently(
                AuditCategory.SAFETY,
                "REAL_MONEY_ROUTE_REJECTED",
                AuditOutcome.DENIED,
                details = mapOf("method" to request.method, "path" to path),
            )
            val pd = problem(HttpStatus.FORBIDDEN, "real-money-prohibited", "Real-money trading does not exist in StrategyForge Version 1.", path)
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.writer.write(mapper.writeValueAsString(pd))
            return
        }
        chain.doFilter(request, response)
    }
}

/**
 * Runs before any bean (including the datasource and Flyway) is created, so a
 * real-money flag in any property source aborts startup immediately (FR-113).
 */
class RealMoneyEnvironmentGuard : org.springframework.boot.env.EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: org.springframework.core.env.ConfigurableEnvironment,
        application: org.springframework.boot.SpringApplication,
    ) {
        val value = environment.getProperty("strategyforge.real-money-trading-enabled") ?: environment.getProperty(RealMoneyPolicy.ENV_FLAG) ?: "false"
        RealMoneyPolicy.assertFlagSafe(value)
    }
}
