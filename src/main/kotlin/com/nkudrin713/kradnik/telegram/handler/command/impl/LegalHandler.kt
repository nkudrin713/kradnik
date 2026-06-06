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
