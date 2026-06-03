package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.OutputType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class DownloadOutputTypeConverter : AttributeConverter<OutputType, String> {
	override fun convertToDatabaseColumn(attribute: OutputType?): String? =
		attribute?.dbValue

	override fun convertToEntityAttribute(dbData: String?): OutputType? =
		dbData?.let(OutputType::fromDb)
}

@Converter
class DownloadJobStatusConverter : AttributeConverter<DownloadJobStatus, String> {
	override fun convertToDatabaseColumn(attribute: DownloadJobStatus?): String? =
		attribute?.dbValue

	override fun convertToEntityAttribute(dbData: String?): DownloadJobStatus? =
		dbData?.let(DownloadJobStatus::fromDb)
}
