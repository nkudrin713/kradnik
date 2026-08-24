package com.nkudrin713.kradnik.telegram.handler.command

import com.nkudrin713.kradnik.telegram.handler.TelegramCallbackContext

interface TelegramCallbackHandler {
    fun supports(context: TelegramCallbackContext): Boolean

    fun handle(context: TelegramCallbackContext)
}
