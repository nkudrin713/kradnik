package com.nkudrin713.kradnik.settings

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class DownloadModeConverter : AttributeConverter<DownloadMode, String> {
    override fun convertToDatabaseColumn(attribute: DownloadMode?): String? =
        attribute?.dbValue

    override fun convertToEntityAttribute(dbData: String?): DownloadMode? =
        dbData?.let(DownloadMode::fromDb)
}
