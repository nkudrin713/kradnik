package com.nkudrin713.kradnik.admin

import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformAvailability
import com.nkudrin713.kradnik.download.platform.PlatformStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView

@RestController
@ConditionalOnProperty(name = ["admin.enabled"], havingValue = "true")
class AdminController(private val snapshots: AdminSnapshots, private val backup: AdminBackup, private val availability: PlatformAvailability) {
    @GetMapping("/login")
    fun login(): ModelAndView = ModelAndView("forward:/login/index.html")

    @GetMapping("/admin", "/admin/")
    fun index(): RedirectView = RedirectView("/admin/index.html")

    @GetMapping("/admin/api/snapshot")
    fun snapshot(): DashboardSnapshot = snapshots.snapshot()

    @GetMapping("/admin/api/services")
    fun services(): List<PlatformStatus> = availability.list()

    @PostMapping("/admin/api/services/{id}")
    fun setService(@PathVariable id: String, @RequestBody request: ServiceToggleRequest): PlatformStatus {
        val platform = DownloadPlatform.entries.firstOrNull { it.dbValue == id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val enabled = request.enabled ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled is required")
        return availability.setEnabled(platform, enabled)
    }

    @GetMapping("/admin/api/backup")
    fun backup(): BackupStatus = backup.status()

    @GetMapping("/admin/api/database-size")
    fun databaseSize(): DatabaseSize = DatabaseSize(backup.databaseSize())

    @GetMapping("/admin/api/backup/estimate")
    fun backupEstimate(): BackupEstimate = backup.estimate()

    @PostMapping("/admin/api/backup")
    fun createBackup(): BackupStatus = backup.start()

    @GetMapping("/admin/api/csrf", "/login/csrf")
    fun csrf(token: CsrfToken): Map<String, String> = mapOf("parameterName" to token.parameterName, "token" to token.token)
}

data class ServiceToggleRequest(val enabled: Boolean? = null)
