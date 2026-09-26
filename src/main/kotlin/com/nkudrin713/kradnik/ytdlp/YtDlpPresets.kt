package com.nkudrin713.kradnik.ytdlp

internal object YtDlpPresets {
    const val YOUTUBE_AUDIO_FORMAT = "ba/bestaudio"
    const val AUDIO_FORMAT_WITH_VIDEO_FALLBACK = "ba/bestaudio/best"
    const val AUDIO_QUALITY_ARG = "--audio-quality"

    val MERGE_MP4_ARGS = listOf("--merge-output-format", "mp4")
    val MP3_AUDIO_ARGS = listOf("-x", "--audio-format", "mp3")

    private val AUDIO_METADATA_ARGS = listOf(
        "--embed-metadata",
        "--embed-thumbnail",
        "--convert-thumbnails",
        "jpg",
    )

    val YOUTUBE_AUDIO_ARGS = MP3_AUDIO_ARGS + AUDIO_METADATA_ARGS
    val PLAYLIST_AUDIO_ARGS = MP3_AUDIO_ARGS + listOf(AUDIO_QUALITY_ARG, "96K") + AUDIO_METADATA_ARGS

    fun withAudioQuality(extraArgs: List<String>, quality: String): List<String> {
        val args = mutableListOf<String>()
        var index = 0
        while (index < extraArgs.size) {
            if (extraArgs[index] == AUDIO_QUALITY_ARG) {
                index += 2
            } else {
                args += extraArgs[index++]
            }
        }
        return args + listOf(AUDIO_QUALITY_ARG, quality)
    }
}
