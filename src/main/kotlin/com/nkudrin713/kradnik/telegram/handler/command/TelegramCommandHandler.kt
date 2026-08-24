package com.nkudrin713.kradnik.telegram.handler.command

import com.nkudrin713.kradnik.telegram.handler.TelegramMessageContext

interface TelegramCommandHandler {

    fun supports(context: TelegramMessageContext): Boolean

    fun handle(context: TelegramMessageContext)
}
