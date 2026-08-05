package com.todoapp.backend.metrics

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registers [ActivityInterceptor].
 *
 * Note there is no @EnableWebMvc here, on purpose: adding it would switch off Spring Boot's MVC
 * auto-configuration wholesale (content negotiation, message converters, static resource handling —
 * which serves the landing page and the legal pages). A bare WebMvcConfigurer only *adds* to the
 * auto-configured setup.
 */
@Configuration
class MetricsWebMvcConfig(private val recorder: ActivityRecorder) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ActivityInterceptor(recorder))
            .addPathPatterns("/**")
            // The panel must not count itself. Without this exclusion every day an operator opens the
            // dashboard adds 1 to that day's DAU — a metrics tool quietly reporting its own use as
            // product usage, and the error grows exactly when you look at it most.
            .excludePathPatterns("/admin/**")
            // Health probes run every minute from an uptime monitor and are not a person.
            .excludePathPatterns("/actuator/**")
    }
}
