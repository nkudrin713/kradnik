package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("telegram.bot")
data class TelegramBotProperties(
    val token: String,
    val apiUrl: String = CLOUD_API_URL,
    val fileApiUrl: String = CLOUD_FILE_API_URL,
    val maxUploadBytes: Long = TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofMinutes(60),
) {
    val normalizedApiUrl: String = apiUrl.trimEnd('/')
    val normalizedFileApiUrl: String = fileApiUrl.trimEnd('/')
    val localApi: Boolean = normalizedApiUrl != CLOUD_API_URL

    init {
        require(token.isNotBlank()) { "telegram.bot.token must not be blank" }
        require(maxUploadBytes in 1..TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES) {
            "telegram.bot.max-upload-bytes must be between 1 and ${TelegramUploadLimits.LOCAL_MAX_UPLOAD_BYTES}"
        }
        require(localApi || maxUploadBytes <= TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES) {
            "telegram.bot.max-upload-bytes must not exceed ${TelegramUploadLimits.CLOUD_MAX_UPLOAD_BYTES} " +
                    "when the cloud API is used"
        }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "telegram.bot.connect-timeout must be positive"
        }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "telegram.bot.request-timeout must be positive"
        }
        validateUrl(apiUrl, "/bot", "telegram.bot.api-url")
        validateUrl(fileApiUrl, "/file/bot", "telegram.bot.file-api-url")
        require(localApi == (normalizedFileApiUrl != CLOUD_FILE_API_URL)) {
            "telegram.bot.api-url and telegram.bot.file-api-url must both use cloud or local Bot API"
        }
        require(!localApi || URI(normalizedApiUrl).authority == URI(normalizedFileApiUrl).authority) {
            "telegram.bot.api-url and telegram.bot.file-api-url must use the same local server"
        }
    }

    private fun validateUrl(value: String, suffix: String, propertyName: String) {
        val normalized = value.trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank() && normalized.endsWith(suffix)) {
            "$propertyName must be an HTTP URL ending with $suffix"
        }
    }

    companion object {
        const val CLOUD_API_URL = "https://api.telegram.org/bot"
        const val CLOUD_FILE_API_URL = "https://api.telegram.org/file/bot"
    }
}
