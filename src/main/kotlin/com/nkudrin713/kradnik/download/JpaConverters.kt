package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.settings.DownloadMode
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class DownloadOutputTypeConverter : AttributeConverter<DownloadOutputType, String> {
	override fun convertToDatabaseColumn(attribute: DownloadOutputType?): String? =
		attribute?.dbValue

	override fun convertToEntityAttribute(dbData: String?): DownloadOutputType? =
		dbData?.let(DownloadOutputType::fromDb)
}

@Converter
class DownloadTaskStatusConverter : AttributeConverter<DownloadTaskStatus, String> {
	override fun convertToDatabaseColumn(attribute: DownloadTaskStatus?): String? =
		attribute?.dbValue

	override fun convertToEntityAttribute(dbData: String?): DownloadTaskStatus? =
		dbData?.let(DownloadTaskStatus::fromDb)
}

@Converter
class DownloadModeConverter : AttributeConverter<DownloadMode, String> {
	override fun convertToDatabaseColumn(attribute: DownloadMode?): String? =
		attribute?.dbValue

	override fun convertToEntityAttribute(dbData: String?): DownloadMode? =
		dbData?.let(DownloadMode::fromDb)
}
