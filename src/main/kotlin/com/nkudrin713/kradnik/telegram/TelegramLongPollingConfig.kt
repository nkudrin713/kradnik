package com.nkudrin713.kradnik.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val GET_UPDATES_LIMIT = 100

@Configuration
@ConditionalOnExpression("'\${telegram.bot.token:}' != ''")
class TelegramLongPollingConfig {
    @Bean
    fun telegramBotsApplication(
        @Value("\${telegram.bot.connect-timeout-seconds:10}")
        connectTimeoutSeconds: Long,
        @Value("\${telegram.bot.read-timeout-seconds:35}")
        readTimeoutSeconds: Long,
        @Value("\${telegram.bot.write-timeout-seconds:30}")
        writeTimeoutSeconds: Long,
    ): TelegramBotsLongPollingApplication =
        TelegramBotsLongPollingApplication(
            { ObjectMapper() },
            {
                val dispatcher = Dispatcher()
                dispatcher.maxRequests = 10
                dispatcher.maxRequestsPerHost = 10

                OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
                    .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                    .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            },
            Executors::newSingleThreadScheduledExecutor,
        )

    @Bean
    fun telegramBotInitializer(
        telegramBotsApplication: TelegramBotsLongPollingApplication,
        longPollingBots: ObjectProvider<List<SpringLongPollingBot>>,
        telegramUrl: ObjectProvider<TelegramUrl>,
        @Value("\${telegram.bot.long-polling-timeout-seconds:20}")
        longPollingTimeoutSeconds: Int,
    ): TelegramBotInitializer =
        object : TelegramBotInitializer(
            telegramBotsApplication,
            longPollingBots.getIfAvailable(Collections::emptyList),
            telegramUrl.getIfAvailable { TelegramUrl.DEFAULT_URL },
        ) {
            override fun afterPropertiesSet() {
                longPollingBots.getIfAvailable(Collections::emptyList).forEach { bot ->
                    telegramBotsApplication.registerBot(
                        bot.botToken,
                        { telegramUrl.getIfAvailable { TelegramUrl.DEFAULT_URL } },
                        { lastReceivedUpdate ->
                            GetUpdates.builder()
                                .limit(GET_UPDATES_LIMIT)
                                .timeout(longPollingTimeoutSeconds)
                                .offset(lastReceivedUpdate + 1)
                                .build()
                        },
                        bot.updatesConsumer,
                    )
                }
            }
        }
}
