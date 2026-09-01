package com.nkudrin713.kradnik.telegram.handler

import com.nkudrin713.kradnik.telegram.DownloadChoiceCoordinator
import com.nkudrin713.kradnik.telegram.PrepareDownloadChoiceCommand
import com.nkudrin713.kradnik.telegram.TelegramDonationSender
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Routes updates received by [TelegramPollingService][com.nkudrin713.kradnik.telegram.TelegramPollingService] to
 * command responses, [DownloadChoiceCoordinator] for direct and guest links, or
 * [DownloadChoiceHandler] for callbacks. Pinned-service messages are removed, and unsupported text receives the short
 * link prompt without entering download preparation.
 */
@Service
class TelegramUpdateHandler(
    private val downloadChoiceCoordinator: DownloadChoiceCoordinator,
    private val downloadChoiceHandler: DownloadChoiceHandler,
    private val telegramSender: TelegramSender,
    private val telegramDonationSender: TelegramDonationSender,
    @Value("\${telegram.donation.url:}")
    private val donationUrl: String,
) {
    fun handle(update: Update) {
        val guestMessage = update.guestMessage()
        val message = update.message()
        when {
            guestMessage?.text() != null -> handleGuestMessage(update, guestMessage)
            message?.pinnedMessage() != null -> telegramSender.deleteMessage(
                chatId = message.chat().id(),
                messageId = message.messageId(),
            )

            message?.text() != null -> handleMessage(update, message)
            update.callbackQuery()?.data() != null -> downloadChoiceHandler.handle(update.callbackQuery())
        }
    }

    private fun handleGuestMessage(update: Update, message: Message) {
        val guestQueryId = message.guestQueryId() ?: return
        val url = guestUrl(message.text())
        if (url == null) {
            telegramSender.answerGuestMessage(guestQueryId, "Нужна ссылка")
            return
        }

        downloadChoiceCoordinator.prepare(
            PrepareDownloadChoiceCommand(
                telegramUserId = message.from().id(),
                telegramChatId = message.chat().id(),
                telegramUpdateId = update.updateId(),
                telegramRequestMessageId = message.messageId(),
                url = url,
                guestQueryId = guestQueryId,
            )
        )
    }

    private fun guestUrl(text: String): String? {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 2 || !parts[0].startsWith("@")) {
            return null
        }
        return parts[1].takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun handleMessage(update: Update, message: Message) {
        val text = message.text().trim()
        val chatId = message.chat().id()

        when {
            text == "/start" -> telegramSender.sendMessage(chatId, "Пришли ссылку на медиа")
            text == "/help" -> telegramSender.sendMessage(chatId, HELP_TEXT)
            text == "/legal" -> telegramSender.sendMessage(chatId, LEGAL_TEXT)
            text == "/donate" -> sendDonation(chatId)
            text.startsWith("http://") || text.startsWith("https://") -> prepareDownload(update, message, text)
            else -> telegramSender.sendMessage(chatId, "Нужна ссылка")
        }
    }

    private fun sendDonation(chatId: Long) {
        if (donationUrl.isBlank()) {
            telegramSender.sendMessage(chatId, "Донат еще не настроен. Пиратская бухгалтерия спит.")
        } else {
            telegramDonationSender.sendMessage(chatId, donationUrl)
        }
    }

    private fun prepareDownload(update: Update, message: Message, url: String) {
        downloadChoiceCoordinator.prepare(
            PrepareDownloadChoiceCommand(
                telegramUserId = message.from().id(),
                telegramChatId = message.chat().id(),
                telegramUpdateId = update.updateId(),
                telegramRequestMessageId = message.messageId(),
                url = url,
            )
        )
    }

    private companion object {
        private val HELP_TEXT = """
            Что умеет бот:
            - Скачать видео в доступном качестве по ссылке.
            - Скачать звук или обложку.
            - Мгновенно отдавать файл без скачивания, если он ранее уже был загружен в Telegram.

            Что не умеет:
            - Качать большие видео. Ориентир: до 20-40 минут, зависит от качества и размера файла.
            - Качать 18+ и закрытый контент.
            - Обходить авторизацию, платный доступ и ограничения платформ.
            - Гарантировать работу с любой ссылкой.

            Как пользоваться:
            1. Отправь ссылку.
            2. Выбери качество видео, звук или обложку.
            3. Дождись файла.
        """.trimIndent()

        private val LEGAL_TEXT = """
            Дисклеймер:
            - Бот является техническим инструментом для обработки ссылок, которые отправляет пользователь.
            - Бот не предназначен для нарушения авторских, смежных или иных прав.
            - Пользователь самостоятельно отвечает за то, что имеет право скачивать, хранить и распространять материалы по отправленным ссылкам.
            - Публичная доступность ссылки, страницы или файла не означает, что материал можно свободно копировать, скачивать или распространять.
            - Автор проекта не размещает пользовательский контент, не формирует каталог материалов и не проверяет правовой статус каждой ссылки.
            - Файлы обрабатываются автоматически на основании ссылки, отправленной пользователем.
            - Донаты являются добровольной поддержкой инфраструктуры проекта и не являются оплатой доступа к какому-либо контенту.
            - Донаты могут использоваться на хостинг, трафик, стабильность работы, новые функции и развитие проекта.
            - Если вы являетесь правообладателем и считаете, что бот используется для нарушения ваших прав, свяжитесь с автором проекта для рассмотрения обращения.
            - Название «Крадник», слова вроде «кража» и пиратская стилистика являются частью ироничного образа проекта и не являются призывом к нарушению закона.
        """.trimIndent()
    }
}
