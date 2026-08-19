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
            var lastConfirmedUpdateId = UpdatesListener.CONFIRMED_UPDATES_NONE
            for (update in updates) {
                try {
                    updateHandler.handle(update)
                    lastConfirmedUpdateId = update.updateId()
                } catch (error: Exception) {
                    log.error("Failed to handle Telegram update: updateId={}", update.updateId(), error)
                    break
                }
            }

            lastConfirmedUpdateId
        }
    }

    @PreDestroy
    fun stop() {
        bot.removeGetUpdatesListener()
    }
}
