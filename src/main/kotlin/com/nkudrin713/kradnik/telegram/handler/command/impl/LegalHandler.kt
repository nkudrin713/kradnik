package com.nkudrin713.kradnik.telegram.handler.command.impl

import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext
import com.nkudrin713.kradnik.telegram.handler.command.TelegramCommandHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private const val LEGAL_COMMAND = "/legal"

@Component
@Order(12)
class LegalHandler(
    private val telegramSender: TelegramSender,
) : TelegramCommandHandler {

    override fun supports(context: TelegramUpdateContext): Boolean {
        return context.text == LEGAL_COMMAND
    }

    override fun handle(context: TelegramUpdateContext) {
        telegramSender.sendMessage(context.chatId, LEGAL_TEXT)
    }

    private companion object {
        private val LEGAL_TEXT = """
            Дисклеймер:
            - Бот предоставляет технический инструмент для загрузки по ссылке.
            - Автор не поддерживает нелегальное распространение контента.
            - Автор не несет ответственности за ссылки, файлы и действия пользователей.
            - Пользователь сам проверяет права на скачивание и распространение материалов.
            - Ссылки обычно берутся из публичных источников, но публичность ссылки не означает право на распространение.
            - Слова "кража", "крадник" и похожие формулировки используются только ради образа проекта. Это не призыв, не пропаганда и не поддержка нарушения закона.
        """.trimIndent()
    }
}
