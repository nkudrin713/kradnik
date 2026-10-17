package com.nkudrin713.kradnik.ytdlp

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.source.MediaSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class YtDlpSelectionsTest {
    @Test
    fun preservesSavedSettingsAndCacheIdentitiesForOriginalPostsAndCollections() {
        for (platform in DownloadPlatform.entries) {
            val base = spec(listOf("--saved-option", "value")).copy(platform = platform, outputType = OutputType.VIDEO)
            val prefix = platform.dbValue
            val examples = mapOf(
                MediaSelection.OriginalVideo to base.copy(presetName = "${prefix}_video_original"),
                MediaSelection.Post("Post text") to base.copy(outputType = OutputType.POST, presetName = "${prefix}_post", postText = "Post text"),
                MediaSelection.VideoCollection to base.copy(outputType = OutputType.POST, presetName = "${prefix}_videos"),
            )
            for ((selection, expected) in examples) {
                assertEquals(expected, YtDlpSelections.select(base, selection), "$platform: $selection")
            }
        }
    }

    @Test
    fun addsAudioQuality() {
        val actual = spec(
            extraArgs = listOf("-x", "--audio-format", "mp3"),
        ).let { YtDlpSelections.select(it, MediaSelection.Audio("40K")) }

        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"), actual.extraArgs)
    }

    @Test
    fun replacesAudioQuality() {
        val actual = spec(
            extraArgs = listOf("-x", "--audio-format", "mp3", "--audio-quality", "96K"),
        ).let { YtDlpSelections.select(it, MediaSelection.Audio("40K")) }

        assertEquals(listOf("-x", "--audio-format", "mp3", "--audio-quality", "40K"), actual.extraArgs)
    }

    private fun spec(extraArgs: List<String>): DownloadSpec {
        return DownloadSpec(
            originalUrl = "https://example.com/raw",
            normalizedUrl = "https://example.com/normalized",
            cacheKey = "cache-key",
            outputType = OutputType.AUDIO,
            platform = DownloadPlatform.YOUTUBE,
            formatSelector = "format",
            extraArgs = extraArgs,
            presetName = "preset",
        )
    }
}
