package com.todoapp.backend.config

import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

@RestController
class RootRedirectController(environment: Environment) {

    // In prod Swagger is disabled (springdoc off), so "/" must not point at a now-404 Swagger UI.
    // Send prod visitors to the public privacy page; dev keeps the Swagger landing for convenience.
    private val target: String =
        if (environment.activeProfiles.contains("prod")) "/legal/privacy.html" else "/swagger-ui/index.html"

    @GetMapping("/")
    fun root(): RedirectView = RedirectView(target)
}
