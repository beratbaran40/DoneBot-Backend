package com.todoapp.backend.admin

import com.todoapp.backend.auth.JwtAuthFilter
import com.todoapp.backend.user.UserRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfigurationSource

/**
 * A dedicated security chain for the /admin endpoints, ordered ahead of the application chain
 * ([com.todoapp.backend.config.SecurityConfig], which is @Order(2)).
 *
 * Splitting the chain — rather than adding an admin matcher to the existing one — keeps the surface the
 * Android app talks to byte-for-byte unchanged: JwtService and JwtAuthFilter are untouched, no new JWT
 * claim is minted, and no existing matcher moves. A request outside the /admin tree never enters this
 * chain at all.
 */
@Configuration
class AdminSecurityConfig {

    @Bean
    @Order(1)
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        jwtAuthFilter: JwtAuthFilter,
        userRepository: UserRepository,
        adminProperties: AdminProperties,
        // Qualified by name: Spring MVC registers mvcHandlerMappingIntrospector, which also implements
        // CorsConfigurationSource, so injecting by type alone is ambiguous and fails context startup.
        @Qualifier(ADMIN_CORS_BEAN) corsConfigurationSource: CorsConfigurationSource,
    ): SecurityFilterChain {
        http
            .securityMatcher(ADMIN_PATHS)
            // Must be called explicitly — defining a SecurityFilterChain disables Spring Security's
            // implicit CORS wiring, and a CorsConfigurationSource bean alone would do nothing. Without
            // it the browser's preflight (an OPTIONS carrying Origin but NO Authorization header) falls
            // through to `hasRole`, the 401 entry point answers, and Chrome reports every request as
            // "preflight does not have HTTP ok status".
            .cors { it.configurationSource(corsConfigurationSource) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth -> auth.anyRequest().hasRole(ADMIN) }
            .headers { headers ->
                headers.frameOptions { fo -> fo.deny() }
                headers.httpStrictTransportSecurity { hsts ->
                    hsts.includeSubDomains(false).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)
                }
            }
            .exceptionHandling { ex ->
                // 401 for "no/!valid token" mirrors the app chain. 403 (below) means "authenticated,
                // but not an admin" — the panel branches on exactly this difference: 401 → try refresh,
                // 403 → stop and show the not-authorised screen instead of a refresh loop.
                ex.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                ex.accessDeniedHandler(forbiddenHandler())
            }
            // JwtAuthFilter populates the Long principal; AdminAuthFilter then decides whether to grant
            // ROLE_ADMIN. Anchoring the second one on AuthorizationFilter guarantees it runs after the
            // first and before `hasRole` is evaluated, without depending on filter-registry ordering.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(
                AdminAuthFilter(userRepository, adminProperties),
                AuthorizationFilter::class.java,
            )
        return http.build()
    }

    /**
     * Emits the same {code,message,data,errorCode} envelope as [com.todoapp.backend.common.BaseResponse]
     * so the panel can parse every response the same way. Written as a constant string rather than via
     * an ObjectMapper: the payload is fixed and contains no request-derived data, so there is nothing to
     * serialise and nothing to inject.
     */
    private fun forbiddenHandler(): AccessDeniedHandler = AccessDeniedHandler { _, response, _ ->
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(FORBIDDEN_BODY)
    }

    internal companion object {
        internal const val ADMIN_CORS_BEAN = "adminCorsConfigurationSource"
        const val ADMIN_PATHS = "/admin/**"
        const val ADMIN = "ADMIN"
        const val HSTS_MAX_AGE_SECONDS = 31_536_000L
        const val FORBIDDEN_BODY =
            """{"code":403,"message":"Admin access required","data":null,"errorCode":"admin_forbidden"}"""
    }
}
