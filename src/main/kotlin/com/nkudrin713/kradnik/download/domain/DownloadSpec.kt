package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.platform.DownloadPlatform

data class DownloadSpec(
    val originalUrl: String,
    val normalizedUrl: String,
    val cacheKey: String,
    val outputType: OutputType,
    val platform: DownloadPlatform,
    val formatSelector: String,
    val extraArgs: List<String> = emptyList(),
    val presetName: String,
) {
    fun withAudioQuality(audioQuality: String): DownloadSpec {
        val args = extraArgs
            .withoutAudioQuality()
            .plus(listOf(AUDIO_QUALITY_ARG, audioQuality))

        return copy(extraArgs = args)
    }

    companion object {
        fun fromJob(job: DownloadJob): DownloadSpec {
            return DownloadSpec(
                originalUrl = job.originalUrl,
                normalizedUrl = job.normalizedUrl,
                cacheKey = job.cacheKey,
                outputType = job.outputType,
                platform = job.platform,
                formatSelector = job.selectedFormat,
                extraArgs = job.downloadExtraArgs,
                presetName = job.downloadPreset,
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
