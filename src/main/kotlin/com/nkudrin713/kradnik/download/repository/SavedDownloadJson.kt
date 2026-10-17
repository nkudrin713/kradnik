package com.nkudrin713.kradnik.download.repository

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.platform.DownloadPlatform

/** Keeps the historical videoId field in job entries and saved menus without annotating application data. */
internal object SavedDownloadJson {
    val mapper = jacksonObjectMapper().addMixIn(PlaylistAudioEntry::class.java, EntryFields::class.java)

    private abstract class EntryFields(
        @JsonProperty("position") position: Int,
        @get:JsonProperty("videoId") @param:JsonProperty("videoId") val sourceId: String,
        @JsonProperty("url") url: String,
        @JsonProperty("title") title: String,
        @JsonProperty("durationSeconds") durationSeconds: Int?,
        @JsonProperty("platform") platform: DownloadPlatform,
    )
}
