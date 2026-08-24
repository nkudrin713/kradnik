package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramMessageContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private const val HELP_COMMAND = "/help"

@Component
@Order(11)
class HelpHandler(
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramMessageContext): Boolean {
        return context.text == HELP_COMMAND
    }

    override fun handle(context: TelegramMessageContext) {
        telegramSender.sendMessage(context.chatId, HELP_TEXT)
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
    }
}
