package com.nkudrin713.kradnik.telegram.localization

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.util.Locale

enum class BotLanguage(
    val code: String,
    val locale: Locale,
) {
    EN("en", Locale.ENGLISH),
    RU("ru", Locale.forLanguageTag("ru-RU")),
    ;

    companion object {
        fun fromCode(code: String?): BotLanguage? {
            return entries.firstOrNull { it.code == code?.lowercase() }
        }

    }
}

@Converter
class BotLanguageConverter : AttributeConverter<BotLanguage, String> {
    override fun convertToDatabaseColumn(attribute: BotLanguage?): String? = attribute?.code

    override fun convertToEntityAttribute(dbData: String?): BotLanguage? =
        dbData?.let(BotLanguage::fromCode)
}
