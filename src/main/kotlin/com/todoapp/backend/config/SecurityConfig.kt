package com.todoapp.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        AntPathRequestMatcher("/swagger-ui/**"),
                        AntPathRequestMatcher("/swagger-ui.html"),
                        AntPathRequestMatcher("/v3/api-docs/**"),
                        AntPathRequestMatcher("/h2-console/**"),
                        AntPathRequestMatcher("/auth/**"),
                        AntPathRequestMatcher("/actuator/health"),
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .headers { it.frameOptions { fo -> fo.sameOrigin() } }
            .httpBasic(Customizer.withDefaults())
        return http.build()
    }
}
