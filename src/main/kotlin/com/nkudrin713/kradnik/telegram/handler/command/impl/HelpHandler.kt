package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private const val HELP_COMMAND = "/help"

@Component
@Order(11)
class HelpHandler(
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text == HELP_COMMAND
    }

    override fun handle(context: TelegramUpdateContext) {
        telegramSender.sendMessage(context.chatId, HELP_TEXT)
    }

    private companion object {
        private val HELP_TEXT = """
            Что умеет бот:
            - Скачать видео по ссылке.
            - Скачать аудио по ссылке.
            - Переключать режим: /mode.
            - Мгновенно отдавать файл без скачивания, если он ранее уже был загружен в Telegram.

            Что не умеет:
            - Качать большие видео. Ориентир: до 20-40 минут, зависит от качества и размера файла.
            - Качать 18+ и закрытый контент.
            - Обходить авторизацию, платный доступ и ограничения платформ.
            - Гарантировать работу с любой ссылкой.

            Как пользоваться:
            1. Выбери режим через /mode.
            2. Отправь ссылку.
            3. Дождись файла.
        """.trimIndent()
    }
}
