package com.nkudrin713.kradnik.admin

import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated
import org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(controllers = [AdminController::class], properties = ["admin.enabled=true", "admin.username=operator"])
@Import(AdminSecurityConfiguration::class)
class AdminSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean
    private lateinit var snapshots: AdminSnapshots

    @Test
    fun anonymousClientsCannotReadStaticPageOrApi() {
        mvc.perform(get("/admin/index.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"))
        mvc.perform(get("/admin/api/snapshot")).andExpect(status().isUnauthorized())
        mvc.perform(get("/admin/api/csrf")).andExpect(status().isUnauthorized())
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(forwardedUrl("/login/index.html"))
        mvc.perform(get("/login/index.html")).andExpect(status().isOk())
        mvc.perform(get("/login/login.js")).andExpect(status().isOk())
        mvc.perform(get("/login/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty)
        mvc.perform(get("/login?error")).andExpect(status().isOk())
        mvc.perform(formLogin().user("operator").password("wrong")).andExpect(unauthenticated())
    }

    @Test
    fun loginCreatesSessionAndLogoutRequiresCsrfThenInvalidatesSession() {
        val now = Instant.now()
        `when`(snapshots.snapshot()).thenReturn(DashboardSnapshot(now, RuntimeView(now, listOf(WorkerView("download-1", "download", "IDLE")), 0, emptyList()), memory = MemoryView(current = MemoryPoint(now, 100, 200, 300, 40, 20, 10, null, null, 2, 4, 60), available = true)))
        val login = mvc.perform(formLogin().user("operator").password("test-admin-password"))
            .andExpect(authenticated().withUsername("operator"))
            .andExpect(redirectedUrl("/admin/index.html")).andReturn()
        val session = login.request.session as MockHttpSession
        mvc.perform(get("/admin/api/snapshot").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.runtime.metadataQueued").value(0))
            .andExpect(jsonPath("$.runtime.workers[0].state").value("IDLE"))
            .andExpect(jsonPath("$.queues").isArray)
            .andExpect(jsonPath("$.outcomes").isArray)
            .andExpect(jsonPath("$.history").isArray)
            .andExpect(jsonPath("$.statistics.restored").value(false))
            .andExpect(jsonPath("$.memory.current.heapUsed").value(100))
            .andExpect(jsonPath("$.memory.current.gcWindowSeconds").value(60))
            .andExpect(jsonPath("$.memory.current.containerUsed").doesNotExist())
            .andExpect(jsonPath("$.memory.available").value(true))
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
        mvc.perform(get("/admin/index.html").session(session)).andExpect(status().isOk())
        mvc.perform(get("/admin/admin.js").session(session)).andExpect(status().isOk())
        mvc.perform(get("/admin/api/csrf").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty)
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden())
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout")).andExpect(unauthenticated())
        mvc.perform(get("/admin/api/snapshot")).andExpect(status().isUnauthorized())
    }

    @Test
    fun loginPostWithoutCsrfIsRejected() {
        mvc.perform(post("/login").param("username", "operator").param("password", "test-admin-password"))
            .andExpect(status().isForbidden())
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun credentials(registry: DynamicPropertyRegistry) {
            val hash = requireNotNull(BCryptPasswordEncoder().encode("test-admin-password"))
            registry.add("admin.password-hash") { hash }
        }
    }
}
