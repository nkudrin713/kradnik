package com.nkudrin713.kradnik.ytdlp.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

@JsonIgnoreProperties(ignoreUnknown = true)
data class YtDlpMetadataDto(
	val id: String?,
	val title: String?,
	val extractor: String?,
	@JsonProperty("webpage_url")
	val webpageUrl: String?,
	val thumbnail: String?,
	val duration: Int?,
	val ext: String?,
	val width: Int?,
	val height: Int?,
	val fps: BigDecimal?,
	val filesize: Long?,
	val vcodec: String?,
	val acodec: String?,
	@JsonProperty("filesize_approx")
	val filesizeApprox: Long?,
	@JsonProperty("format_id")
	val formatId: String?,
	val format: String?,
	@JsonProperty("requested_formats")
	val requestedFormats: List<YtDlpFormatDto>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YtDlpFormatDto(
	@JsonProperty("format_id")
	val formatId: String?,
	val ext: String?,
	val width: Int?,
	val height: Int?,
	val filesize: Long?,
	@JsonProperty("filesize_approx")
	val filesizeApprox: Long?,
)
