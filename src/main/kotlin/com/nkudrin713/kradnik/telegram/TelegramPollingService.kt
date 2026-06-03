package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateHandler
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class TelegramPollingService(
    private val bot: TelegramBot,
    private val updateHandler: TelegramUpdateHandler
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun start() {
        bot.setUpdatesListener { updates ->
            updates.forEach { update ->
                runCatching {
                    updateHandler.handle(update)
                }.onFailure {
                    log.error("Failed to handle Telegram update", it)
                }
            }

            UpdatesListener.CONFIRMED_UPDATES_ALL
        }
    }

    @PreDestroy
    fun stop() {
        bot.removeGetUpdatesListener()
    }
}