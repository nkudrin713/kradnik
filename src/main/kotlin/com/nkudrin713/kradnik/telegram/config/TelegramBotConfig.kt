package com.nkudrin713.kradnik.telegram.config

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.pengrad.telegrambot.TelegramBot
import okhttp3.OkHttpClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TelegramBotProperties::class)
class TelegramBotConfig(
    private val properties: TelegramBotProperties,
) {
    @Bean
    fun telegramBot(): TelegramBot {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(properties.connectTimeout)
            .writeTimeout(properties.requestTimeout)
            .readTimeout(properties.requestTimeout)
            .callTimeout(properties.requestTimeout)
            .build()

        return TelegramBot.Builder(properties.token)
            .apiUrl(properties.normalizedApiUrl)
            .fileApiUrl(properties.normalizedFileApiUrl)
            .okHttpClient(httpClient)
            .build()
    }

    @Bean
    fun telegramUploadLimits(): TelegramUploadLimits {
        return TelegramUploadLimits(
            maxUploadBytes = properties.maxUploadBytes,
            localMode = properties.localApi,
        )
    }
}
