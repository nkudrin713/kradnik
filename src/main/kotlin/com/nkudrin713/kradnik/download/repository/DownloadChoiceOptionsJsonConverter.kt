package com.nkudrin713.kradnik.download.repository

import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class DownloadChoiceOptionsJsonConverter : AttributeConverter<List<DownloadChoiceOptionSnapshot>, String> {
    private val objectMapper = SavedDownloadJson.mapper

    override fun convertToDatabaseColumn(attribute: List<DownloadChoiceOptionSnapshot>?): String {
        return objectMapper.writeValueAsString(attribute.orEmpty())
    }

    override fun convertToEntityAttribute(dbData: String?): List<DownloadChoiceOptionSnapshot> {
        return dbData?.takeIf { it.isNotBlank() }?.let { objectMapper.readValue(it) } ?: emptyList()
    }
}
