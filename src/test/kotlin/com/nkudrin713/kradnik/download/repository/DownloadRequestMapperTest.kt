package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionsJsonConverter
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadRequestMapperTest {
    @Test
    fun oldMenuPreservesItsSelectionAndVersionsOnlyTheNewJobKey() {
        val options = DownloadChoiceOptionsJsonConverter().convertToEntityAttribute(
            """[{
                "key":"video_original", "label":"Original", "sizeBytes":null,
                "approximateSize":false, "available":true, "unavailableReason":null,
                "spec":{
                    "originalUrl":"https://vk.com/video-1_2?context=old",
                    "normalizedUrl":"https://vk.com/video-1_2",
                    "cacheKey":"legacy-video", "outputType":"VIDEO", "platform":"VK",
                    "formatSelector":"old-video+old-audio", "extraArgs":["--merge-output-format","mp4"],
                    "presetName":"old-preset"
                }
            }]""",
        )
        val spec = options.single().spec
        val request = DownloadRequestMapper.single(spec)
        assertEquals("old-video+old-audio", request.source.formatSelector)
        assertEquals(listOf("--merge-output-format", "mp4"), request.source.extraArgs)
        assertEquals("https://vk.com/video-1_2?context=old", request.source.originalUrl)
        assertEquals("old-preset", request.source.presetName)
        assertEquals("legacy-video", request.cacheKey)
        assertEquals("legacy-video:telegram-video-h264-v1", ResultKeyFactory.forNewJob(spec.cacheKey, spec.outputType))
    }

    @Test
    fun queuedJobIsNotReversionedAndExecutionQualityDoesNotMutateItsSnapshot() {
        val job = DownloadJob(
            cacheKey = "legacy:telegram-video-h264-v1",
            selectedFormat = "old-format",
            downloadExtraArgs = listOf("--audio-quality", "40K", "--embed-metadata"),
            sourcePostText = "Saved description",
        )
        val request = DownloadRequestMapper.single(job)
        val adjusted = request.withAudioQuality("96K")
        assertEquals(job.cacheKey, request.cacheKey)
        assertEquals(job.cacheKey, adjusted.cacheKey)
        assertEquals("Saved description", request.postText)
        assertEquals("old-format", adjusted.source.formatSelector)
        assertEquals(listOf("--embed-metadata", "--audio-quality", "96K"), adjusted.source.extraArgs)
        assertEquals(listOf("--audio-quality", "40K", "--embed-metadata"), job.downloadExtraArgs)
    }

    @Test
    fun playlistSnapshotRetainsFailedPositionsAndDeliveryMode() {
        val entries = listOf(PlaylistAudioEntry(3, "id", "url", "Title", 60))
        val results = listOf(PlaylistAudioResult(3, error = "Unavailable"))
        val job = DownloadJob(workloadType = DownloadWorkloadType.PLAYLIST_AUDIO, playlistEntries = entries, playlistResults = results, playlistTitle = "Playlist", playlistDeliveryMode = PlaylistDeliveryMode.ZIP)
        val request = DownloadRequestMapper.playlist(job)
        assertEquals(entries, request.entries)
        assertEquals(results, request.completedEntries)
        assertEquals("Playlist", request.title)
        assertEquals(PlaylistDeliveryMode.ZIP, request.deliveryMode)
        assertFailsWith<IllegalArgumentException> { DownloadRequestMapper.single(job) }
        assertFailsWith<IllegalArgumentException> { DownloadRequestMapper.playlist(DownloadJob()) }
    }
}
