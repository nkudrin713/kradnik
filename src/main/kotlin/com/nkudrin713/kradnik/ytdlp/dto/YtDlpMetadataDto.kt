package com.nkudrin713.kradnik.ytdlp.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

@JsonIgnoreProperties(ignoreUnknown = true)
data class YtDlpMetadataDto(
    val title: String?,
    val extractor: String?,
    val thumbnail: String?,
    val duration: BigDecimal?,
    val width: Int?,
    val height: Int?,
    val filesize: Long?,
    @JsonProperty("filesize_approx")
    val filesizeApprox: Long?,
    val track: String?,
    val artist: String?,
    val uploader: String?,
    val channel: String?,
    @JsonProperty("requested_formats")
    val requestedFormats: List<YtDlpFormatDto>?,
    val formats: List<YtDlpFormatDto>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YtDlpFormatDto(
    @JsonProperty("format_id")
    val formatId: String?,
    val ext: String?,
    val height: Int?,
    val fps: BigDecimal?,
    val filesize: Long?,
    @JsonProperty("filesize_approx")
    val filesizeApprox: Long?,
    val vcodec: String?,
    val acodec: String?,
    val tbr: BigDecimal?,
    val vbr: BigDecimal?,
    val abr: BigDecimal?,
)
