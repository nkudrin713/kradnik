package com.nkudrin713.kradnik.localization

import java.util.Locale

enum class BotLanguage(
    val code: String,
    val locale: Locale,
) {
    EN("en", Locale.ENGLISH),
    RU("ru", Locale.forLanguageTag("ru-RU")),
    RU_INFORMAL("ru-informal", Locale.forLanguageTag("ru-RU-INFORMAL")),
    ;

    companion object {
        fun fromCode(code: String?): BotLanguage? {
            return entries.firstOrNull { it.code == code?.lowercase() }
        }
    }
}
