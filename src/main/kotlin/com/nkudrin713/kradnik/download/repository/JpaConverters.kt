package com.nkudrin713.kradnik.download.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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

@Converter
class StringListJsonConverter : AttributeConverter<List<String>, String> {
	private val objectMapper = jacksonObjectMapper()

	override fun convertToDatabaseColumn(attribute: List<String>?): String =
		objectMapper.writeValueAsString(attribute ?: emptyList<String>())

	override fun convertToEntityAttribute(dbData: String?): List<String> =
		dbData?.takeIf { it.isNotBlank() }?.let { objectMapper.readValue(it) } ?: emptyList()
}
