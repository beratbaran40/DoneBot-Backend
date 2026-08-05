package com.todoapp.backend.admin

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * CORS for the browser-based admin panel — the first and only browser client this API has.
 *
 * The API shipped deliberately without any CorsConfigurationSource: the Android app sends no Origin
 * header, so emitting no Access-Control-Allow-Origin left every cross-origin read blocked by default.
 * This narrows that default rather than removing it — configuration is registered for the /admin tree
 * and the three auth endpoints the panel actually calls, and for nothing else. A request to any other
 * path still gets no CORS headers, exactly as before.
 *
 * Two things here are load-bearing:
 *
 * 1. Registering this bean is NOT enough on its own. Once an application defines its own
 *    SecurityFilterChain, Spring Security does not enable CORS implicitly — `http.cors { }` must be
 *    called on **both** chains. Forgetting it on the auth chain breaks login while the rest of the
 *    panel appears to work, which is a confusing failure to debug.
 *
 * 2. `allowCredentials` stays false. The panel authenticates with a Bearer header, not cookies, so it
 *    never needs credentialed CORS — and keeping it off removes any chance of the
 *    allowedOrigins("*") + allowCredentials(true) account-takeover combination being introduced later.
 */
@Configuration
class AdminCorsConfig {

    @Bean
    fun adminCorsConfigurationSource(properties: AdminProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.corsOrigins()
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            // Only what the panel actually sends. Content-Type is required because a JSON body makes
            // the request non-simple, which is also why /auth/login needs CORS at all.
            allowedHeaders = listOf("Authorization", "Content-Type")
            // Lets the panel show the correlation id on an error screen so a report is traceable.
            exposedHeaders = listOf("X-Request-Id")
            allowCredentials = false
            maxAge = PREFLIGHT_CACHE_SECONDS
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration(ADMIN_PATHS, configuration)
            // Avatars are fetched with <img src>, which is not a CORS-gated read, so GET
            // /users/{id}/avatar deliberately gets no configuration here.
            registerCorsConfiguration("/auth/login", configuration)
            // Google is the panel's primary sign-in. It needs no new backend code at all: the Android
            // app already requests its ID token with the *web* client as serverClientId, and
            // GoogleAuthService validates on audience alone — so a token minted in a browser by the
            // same web client verifies identically. The only external step is adding the panel's origin
            // to that client's Authorized JavaScript origins in the Google Cloud console.
            registerCorsConfiguration("/auth/google", configuration)
            // Email+password stays wired as the break-glass path, for the day a Google-side
            // misconfiguration would otherwise lock the operator out of their own panel.
            registerCorsConfiguration("/auth/refresh", configuration)
            registerCorsConfiguration("/auth/logout", configuration)
        }
    }

    private companion object {
        const val ADMIN_PATHS = "/admin/**"
        const val PREFLIGHT_CACHE_SECONDS = 3600L
    }
}
