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
    fun withAudioQuality(audioQuality: String): DownloadRequest {
        val args = extraArgs
            .withoutAudioQuality()
            .plus(listOf(AUDIO_QUALITY_ARG, audioQuality))

        return copy(extraArgs = args)
    }

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

        private const val AUDIO_QUALITY_ARG = "--audio-quality"

        private fun List<String>.withoutAudioQuality(): List<String> {
            val result = mutableListOf<String>()
            var index = 0
            while (index < size) {
                if (this[index] == AUDIO_QUALITY_ARG) {
                    index += 2
                } else {
                    result += this[index]
                    index += 1
                }
            }

            return result
        }
    }
}
