package com.nkudrin713.kradnik.admin

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.session.HttpSessionEventPublisher
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdminSecurityConfiguration {
    @Bean
    fun adminSessionEvents(): HttpSessionEventPublisher = HttpSessionEventPublisher()

    @Bean
    fun adminPasswordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun adminUsers(environment: Environment): UserDetailsService {
        if (!environment.getProperty("admin.enabled", Boolean::class.java, false)) return InMemoryUserDetailsManager()
        val username = environment.getProperty("admin.username", "")
        val hash = environment.getProperty("admin.password-hash", "")
        require(username.isNotBlank()) { "ADMIN_USERNAME is required when admin is enabled" }
        require(Regex("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}").matches(hash)) { "ADMIN_PASSWORD_HASH must be a BCrypt hash" }
        require(hash.substring(4, 6).toInt() in 10..14) { "ADMIN_PASSWORD_HASH must use BCrypt cost 10 through 14" }
        return InMemoryUserDetailsManager(User.withUsername(username).password(hash).roles("ADMIN").build())
    }

    @Bean
    fun adminSecurity(http: HttpSecurity, environment: Environment): SecurityFilterChain {
        http.requestCache { it.requestCache(NullRequestCache()) }
        http.headers { headers ->
            headers.contentSecurityPolicy { it.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'") }
        }
        if (!environment.getProperty("admin.enabled", Boolean::class.java, false)) {
            http.authorizeHttpRequests { it.anyRequest().denyAll() }
            http.exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.NOT_FOUND)) }
            return http.build()
        }
        http.authorizeHttpRequests {
            it.requestMatchers("/login", "/login/**", "/error").permitAll()
                .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                .anyRequest().denyAll()
        }
        http.formLogin { it.loginPage("/login").defaultSuccessUrl("/admin/index.html", true).permitAll() }
        http.logout { it.logoutSuccessUrl("/login?logout").permitAll() }
        http.sessionManagement { it.maximumSessions(3) }
        http.exceptionHandling {
            it.defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**"))
            it.defaultAuthenticationEntryPointFor(LoginUrlAuthenticationEntryPoint("/login"), AnyRequestMatcher.INSTANCE)
        }
        http.addFilterBefore(AdminLoginThrottle(), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

/** One bounded global budget; no client-controlled IP map or forwarded-header trust. */
internal class AdminLoginThrottle(private val clock: Clock = Clock.systemUTC()) : OncePerRequestFilter() {
    private var windowStart = clock.instant()
    private var attempts = 0

    @Synchronized
    private fun allow(): Boolean {
        val now = clock.instant()
        if (!now.isBefore(windowStart.plusSeconds(60))) {
            windowStart = now
            attempts = 0
        }
        if (attempts >= 10) return false
        attempts++
        return true
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.method == "POST" && request.servletPath == "/login" && !allow()) {
            response.status = 429
            response.setHeader("Retry-After", "60")
            return
        }
        chain.doFilter(request, response)
    }
}
