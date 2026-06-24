package com.todoapp.backend.config

import com.todoapp.backend.auth.JwtAuthFilter
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
    FacebookOauthProperties::class,
    FirebaseProperties::class,
)
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtAuthFilter: JwtAuthFilter): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/",
                        "/error",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/h2-console/**",
                        "/auth/**",
                        "/actuator/health",
                        "/legal/**",
                    ).permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/users/*/avatar").permitAll()
                    .anyRequest().authenticated()
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
