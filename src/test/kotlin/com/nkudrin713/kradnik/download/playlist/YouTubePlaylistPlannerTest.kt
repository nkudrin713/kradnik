package com.nkudrin713.kradnik.download.playlist

import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubePlaylistPlannerTest {
    private val ytDlpService = mockk<YtDlpService>()
    private val planner = YouTubePlaylistPlanner(
        ytDlpService,
        TelegramUploadLimits(2_000_000_000, localMode = true),
        telegramMessages(),
    )

    @Test
    fun buildsFixedQualityAudioOption() = runTest {
        coEvery { ytDlpService.extractPlaylistMetadata(URL) } returns playlist(3)

        val plan = assertNotNull(planner.planOrNull(URL, BotLanguage.RU))

        assertEquals(2, plan.options.size)
        assertEquals(PlaylistDeliveryMode.ZIP, plan.options.last().spec.playlistDeliveryMode)
        assertEquals("Все 3 в ZIP", plan.options.last().label)
        assertEquals("Podcast", plan.options.last().spec.playlistTitle)
        assertTrue(plan.options.last().spec.cacheKey.endsWith(":zip"))
        assertEquals(3, plan.mediaInfo.playlistCount)
        assertEquals(180, plan.mediaInfo.durationSeconds)
        assertEquals(2_160_000, plan.mediaInfo.estimatedSizeBytes)
        assertEquals("Скачать все 3", plan.options.first().label)
        assertEquals(DownloadWorkloadType.PLAYLIST_AUDIO, plan.options.first().spec.workloadType)
        assertEquals(3, plan.options.first().spec.playlistEntries.size)
        assertEquals(
            listOf("-x", "--audio-format", "mp3", "--audio-quality", "96K", "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg"),
            plan.options.first().spec.extraArgs,
        )
    }

    @Test
    fun offersFirstAndLastHundredForLongPlaylist() = runTest {
        coEvery { ytDlpService.extractPlaylistMetadata(URL) } returns playlist(150)

        val plan = assertNotNull(planner.planOrNull(URL, BotLanguage.EN))

        assertEquals(listOf("playlist_first", "playlist_first_zip", "playlist_last", "playlist_last_zip"), plan.options.map { it.key })
        assertEquals(1, plan.options.first().spec.playlistEntries.first().position)
        assertEquals(100, plan.options.first().spec.playlistEntries.last().position)
        assertEquals(51, plan.options.last().spec.playlistEntries.first().position)
        assertEquals(150, plan.options.last().spec.playlistEntries.last().position)
    }

    @Test
    fun rejectsArchiveByTotalSizeWhileAllowingSeparateTracks() = runTest {
        coEvery { ytDlpService.extractPlaylistMetadata(URL) } returns playlist(3)
        val smallLimitPlanner = YouTubePlaylistPlanner(ytDlpService, TelegramUploadLimits(1_000_000), telegramMessages())
        val plan = assertNotNull(smallLimitPlanner.planOrNull(URL, BotLanguage.RU))
        assertTrue(plan.options.first().available)
        assertFalse(plan.options.last().available)
        assertNotNull(plan.options.last().unavailableReason)
    }

    @Test
    fun unknownDurationsDoNotProduceAnArchiveSizeEstimate() = runTest {
        val metadata = playlist(2)
        coEvery { ytDlpService.extractPlaylistMetadata(URL) } returns metadata.copy(
            entries = metadata.entries!!.map { it.copy(duration = null) },
        )
        val plan = assertNotNull(planner.planOrNull(URL, BotLanguage.EN))
        assertTrue(plan.options.last().available)
        assertNull(plan.options.last().sizeBytes)
    }

    private fun playlist(count: Int) = YtDlpMetadataDto(
        title = "Podcast",
        thumbnail = null,
        duration = null,
        width = null,
        height = null,
        filesize = null,
        filesizeApprox = null,
        track = null,
        artist = null,
        uploader = null,
        channel = null,
        requestedFormats = null,
        entries = (1..count).map { index ->
            YtDlpMetadataDto(
                id = "video-$index",
                title = "Episode $index",
                thumbnail = null,
                duration = BigDecimal.valueOf(60),
                width = null,
                height = null,
                filesize = null,
                filesizeApprox = null,
                track = null,
                artist = null,
                uploader = null,
                channel = null,
                requestedFormats = null,
            )
        },
    )

    private companion object {
        const val URL = "https://www.youtube.com/playlist?list=PL123"
    }
}
