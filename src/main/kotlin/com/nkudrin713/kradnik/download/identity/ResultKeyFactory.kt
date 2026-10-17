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
    fun playlistZip(base: String): String = "$base:zip"
    fun forNewJob(menuKey: String, output: OutputType): String = when (output) {
        OutputType.VIDEO -> TelegramVideoPolicy.versionCacheKey(menuKey)
        OutputType.POST -> TelegramVideoPolicy.versionCacheKey("$menuKey:post:v2")
        else -> menuKey
    }
}
