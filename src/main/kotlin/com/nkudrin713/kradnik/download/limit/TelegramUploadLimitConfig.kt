package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TelegramUploadLimitConfig {
    @Bean
    fun telegramUploadLimits(properties: TelegramBotProperties): TelegramUploadLimits {
        return TelegramUploadLimits(
            maxUploadBytes = properties.maxUploadBytes,
            localMode = properties.localMode,
        )
    }
}
