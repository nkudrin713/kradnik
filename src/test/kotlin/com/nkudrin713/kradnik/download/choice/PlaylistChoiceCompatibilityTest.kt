package com.nkudrin713.kradnik.download.choice

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistChoiceCompatibilityTest {
    private val converter = DownloadChoiceOptionsJsonConverter()

    @Test
    fun roundTripsArchiveSelectionIncludingTitle() {
        val restored = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(listOf(option()))).single()
        assertEquals(PlaylistDeliveryMode.ZIP, restored.spec.playlistDeliveryMode)
        assertEquals("My playlist", restored.spec.playlistTitle)
    }

    @Test
    fun oldSavedMenusKeepAudioDelivery() {
        val json = jacksonObjectMapper().readTree(converter.convertToDatabaseColumn(listOf(option())))
        val spec = json[0]["spec"] as com.fasterxml.jackson.databind.node.ObjectNode
        spec.remove(listOf("playlistDeliveryMode", "playlistTitle"))
        val restored = converter.convertToEntityAttribute(json.toString()).single()
        assertEquals(PlaylistDeliveryMode.AUDIO_MESSAGES, restored.spec.playlistDeliveryMode)
        assertNull(restored.spec.playlistTitle)
    }

    private fun option() = DownloadChoiceOptionSnapshot(
        key = "playlist_all_zip",
        label = "ZIP",
        sizeBytes = null,
        approximateSize = true,
        available = true,
        unavailableReason = null,
        spec = DownloadSpec(
            originalUrl = "https://youtube.com/playlist?list=PL1",
            normalizedUrl = "https://youtube.com/playlist?list=PL1",
            cacheKey = "playlist:zip",
            outputType = OutputType.AUDIO,
            platform = DownloadPlatform.YOUTUBE,
            formatSelector = "bestaudio",
            presetName = "playlist",
            workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
            playlistDeliveryMode = PlaylistDeliveryMode.ZIP,
            playlistTitle = "My playlist",
        ),
    )
}
