package com.nkudrin713.kradnik.ytdlp

import com.nkudrin713.kradnik.download.domain.MediaFormat
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.source.PreparedSource
import com.nkudrin713.kradnik.ytdlp.YtDlpFormatDto
import com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto

data class YtDlpPreparedSource(override val metadata: MediaMetadata) : PreparedSource

internal fun YtDlpMetadataDto.toMediaMetadata(): MediaMetadata = MediaMetadata(
    title = title,
    thumbnail = thumbnail,
    duration = duration,
    width = width,
    height = height,
    filesize = filesize,
    filesizeApprox = filesizeApprox,
    track = track,
    artist = artist,
    uploader = uploader,
    channel = channel,
    requestedFormats = requestedFormats?.map { it.toMediaFormat() },
    formats = formats?.map { it.toMediaFormat() },
    description = description,
)

private fun YtDlpFormatDto.toMediaFormat(): MediaFormat = MediaFormat(
    formatId = formatId,
    ext = ext,
    height = height,
    fps = fps,
    filesize = filesize,
    filesizeApprox = filesizeApprox,
    vcodec = vcodec,
    acodec = acodec,
    tbr = tbr,
    vbr = vbr,
    abr = abr,
)
