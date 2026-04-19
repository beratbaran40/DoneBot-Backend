package com.todoapp.backend.config

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

@RestController
class RootRedirectController {

    @GetMapping("/")
    fun root(): RedirectView = RedirectView("/swagger-ui/index.html")
}
