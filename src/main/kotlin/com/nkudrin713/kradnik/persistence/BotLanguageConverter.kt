package com.nkudrin713.kradnik.persistence

import com.nkudrin713.kradnik.localization.BotLanguage
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class BotLanguageConverter : AttributeConverter<BotLanguage, String> {
    override fun convertToDatabaseColumn(attribute: BotLanguage?): String? = attribute?.code

    override fun convertToEntityAttribute(dbData: String?): BotLanguage? = dbData?.let(BotLanguage::fromCode)
}
