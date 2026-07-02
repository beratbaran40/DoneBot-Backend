package com.todoapp.backend.config

import com.todoapp.backend.auth.JwtAuthFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableConfigurationProperties(
    JwtProperties::class,
    GoogleOauthProperties::class,
    FirebaseProperties::class,
)
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthFilter: JwtAuthFilter,
        @Value("\${springdoc.api-docs.enabled:true}") swaggerEnabled: Boolean,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/",
                        "/error",
                        "/h2-console/**",
                        "/auth/**",
                        "/reset-password",
                        // Liveness/readiness probes (JVM-only, no DB hit) must stay reachable by an
                        // external uptime monitor without a JWT. Safe: show-details=never and only the
                        // `health` endpoint is on the management exposure list, so no internals leak.
                        "/actuator/health",
                        "/actuator/health/**",
                        "/legal/**",
                        "/index.html",
                        "/assets/**",
                        // Digital Asset Links for https App Link verification (reset-password deep link).
                        "/.well-known/**",
                    ).permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/users/*/avatar").permitAll()
                // Open Swagger UI + OpenAPI JSON only when springdoc actually serves them (dev). In prod
                // springdoc is disabled; permitting these would let /swagger-ui/index.html reach a
                // half-initialised resource handler and 500 — leaving them OUT of permitAll → clean 401.
                if (swaggerEnabled) {
                    auth.requestMatchers(
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                    ).permitAll()
                }
                auth.anyRequest().authenticated()
            }
            .headers { headers ->
                headers.frameOptions { fo -> fo.sameOrigin() }
                // HSTS: instruct browsers to only ever reach this host over HTTPS. Takes effect once
                // forward-headers-strategy (prod) makes Spring treat proxied requests as secure.
                // includeSubDomains stays off so the policy can't spill onto sibling *.candroid.dev hosts.
                headers.httpStrictTransportSecurity { hsts ->
                    hsts.includeSubDomains(false).maxAgeInSeconds(31_536_000L)
                }
            }
            .exceptionHandling {
                // Default Http403ForbiddenEntryPoint returns 403 for unauthenticated requests,
                // which the OkHttp Authenticator on the client doesn't recognize (it only fires
                // on 401). Return 401 so the client refresh-on-401 path runs as designed.
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
