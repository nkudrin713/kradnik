package com.nkudrin713.kradnik.download.identity

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.video.TelegramVideoPolicy

/** Existing cache identities. Menu identities are finalized only when creating a new job. */
object ResultKeyFactory {
    fun source(identity: String, output: OutputType, preset: String): String = "$identity:${output.dbValue}:$preset"
    fun choice(base: String, suffix: String): String = "$base:$suffix"
    fun optionSuffix(key: String, preset: String): String = "choice:$key:$preset"
    fun audioSuffix(quality: String): String = "audio:$quality"
    fun coverSuffix(): String = "cover:v1"
    fun playlist(id: String, range: String): String = "youtube:playlist:$id:audio:96:$range"
    fun playlistZip(base: String): String = "$base:zip"
    fun playlistAudioEntry(videoId: String): String = choice(source("youtube:video:$videoId", OutputType.AUDIO, "youtube_audio"), audioSuffix("96K"))
    fun forNewJob(menuKey: String, output: OutputType): String = if (output == OutputType.VIDEO) TelegramVideoPolicy.versionCacheKey(menuKey) else menuKey
}
