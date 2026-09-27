package app.strategyforge.identity

import app.strategyforge.common.web.problem
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.filter.OncePerRequestFilter

/** Resolves `Authorization: Bearer <token>` to the owner principal (opaque server-side sessions, D-004). */
class SessionAuthenticationFilter(
    private val identity: IdentityService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
            val token = header.substring(7).trim()
            if (token.length in 20..200) {
                identity.authenticate(token)?.let { SecurityContextHolder.getContext().authentication = OwnerAuthentication(it) }
            }
        }
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}

@Configuration
class SecurityConfig(
    private val mapper: ObjectMapper,
) {
    /** Argon2id (section 15). OWASP minimum parameters: m=19 MiB, t=2, p=1. */
    @Bean
    fun passwordEncoder(
        @Value("\${strategyforge.auth.argon2-memory-kib:19456}") memoryKib: Int,
        @Value("\${strategyforge.auth.argon2-iterations:2}") iterations: Int,
    ): Argon2PasswordEncoder = Argon2PasswordEncoder(16, 32, 1, memoryKib, iterations)

    @Bean
    fun filterChain(
        http: HttpSecurity,
        identity: IdentityService,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() } // bearer-token API without cookies
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .anonymous { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .headers { h ->
                h.contentTypeOptions { }
                h.frameOptions { it.deny() }
                h.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                h.cacheControl { }
            }.addFilterBefore(SessionAuthenticationFilter(identity), UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { req, res, _ -> write(res, req, HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required") }
                it.accessDeniedHandler { req, res, _ -> write(res, req, HttpStatus.FORBIDDEN, "permission-denied", "Permission denied") }
            }.authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                it.requestMatchers("/v1/bootstrap", "/v1/auth/login", "/v1/auth/recover").permitAll()
                it.requestMatchers("/error").permitAll()
                it.requestMatchers("/v1/**", "/v3/api-docs", "/v3/api-docs/**").authenticated()
                it.anyRequest().denyAll()
            }.build()

    private fun write(
        res: HttpServletResponse,
        req: HttpServletRequest,
        status: HttpStatus,
        code: String,
        detail: String,
    ) {
        res.status = status.value()
        res.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        res.writer.write(mapper.writeValueAsString(problem(status, code, detail, req.requestURI)))
    }
}
