package com.nkudrin713.kradnik.admin

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView

@RestController
@ConditionalOnProperty(name = ["admin.enabled"], havingValue = "true")
class AdminController(private val snapshots: AdminSnapshots) {
    @GetMapping("/login")
    fun login(): ModelAndView = ModelAndView("forward:/login/index.html")

    @GetMapping("/admin", "/admin/")
    fun index(): RedirectView = RedirectView("/admin/index.html")

    @GetMapping("/admin/api/snapshot")
    fun snapshot(): DashboardSnapshot = snapshots.snapshot()

    @GetMapping("/admin/api/csrf", "/login/csrf")
    fun csrf(token: CsrfToken): Map<String, String> = mapOf("parameterName" to token.parameterName, "token" to token.token)
}
