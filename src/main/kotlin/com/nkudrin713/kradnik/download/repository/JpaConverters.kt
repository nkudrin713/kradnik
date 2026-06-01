package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadTaskStatus
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
