package com.nkudrin713.kradnik.telegram.handler.command

import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateContext

interface TelegramCommandHandler {

    fun supports(context: TelegramUpdateContext): Boolean

    fun handle(context: TelegramUpdateContext)
}