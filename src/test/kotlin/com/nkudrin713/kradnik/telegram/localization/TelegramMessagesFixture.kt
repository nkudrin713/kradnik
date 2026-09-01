package com.nkudrin713.kradnik.telegram.localization

import org.springframework.context.support.ResourceBundleMessageSource

fun telegramMessages(): TelegramMessages {
    val messageSource = ResourceBundleMessageSource().apply {
        setBasename("i18n/messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    return TelegramMessages(messageSource)
}
