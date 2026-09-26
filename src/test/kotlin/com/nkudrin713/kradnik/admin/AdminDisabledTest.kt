package com.nkudrin713.kradnik.admin

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(properties = ["admin.enabled=false"])
@Import(AdminSecurityConfiguration::class)
class AdminDisabledTest @Autowired constructor(private val mvc: MockMvc) {
    @Test
    fun disabledAdminHasNoPublicLoginStaticContentOrApi() {
        listOf("/login", "/login/index.html", "/login/csrf", "/admin/index.html", "/admin/api/snapshot").forEach {
            mvc.perform(get(it)).andExpect(status().isNotFound())
        }
    }
}
