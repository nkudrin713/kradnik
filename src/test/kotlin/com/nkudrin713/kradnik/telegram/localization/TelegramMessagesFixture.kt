package com.nkudrin713.kradnik.telegram.localization

import com.nkudrin713.kradnik.localization.Messages
import org.springframework.context.support.ResourceBundleMessageSource

fun telegramMessages(): Messages {
    val messageSource = ResourceBundleMessageSource().apply {
        setBasename("i18n/messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    return Messages(messageSource)
}
