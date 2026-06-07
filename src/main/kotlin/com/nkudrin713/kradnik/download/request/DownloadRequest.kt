package com.nkudrin713.kradnik.download.request

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType

data class DownloadRequest(
    val originalUrl: String,
    val normalizedUrl: String,
    val outputType: OutputType,
    val formatSelector: String,
    val extraArgs: List<String> = emptyList(),
    val presetName: String,
) {
    companion object {
        fun fromJob(job: DownloadJob): DownloadRequest {
            val selectedFormat = requireNotNull(job.selectedFormat?.takeIf { it.isNotBlank() }) {
                "Download job selected format is missing"
            }

            return DownloadRequest(
                originalUrl = job.originalUrl,
                normalizedUrl = job.normalizedUrl,
                outputType = job.outputType,
                formatSelector = selectedFormat,
                extraArgs = job.downloadExtraArgs,
                presetName = job.downloadPreset ?: "default",
            )
        }
    }
}
