package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.playlist.PlaylistCompletion
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.download.service.DownloadJobService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramResultCacheTest {
    @Test
    fun roundTripsMixedPostReceiptsAndVersionsPostCacheSeparately() {
        val items = listOf(CachedPostItem(PostMediaKind.VIDEO, "video"), CachedPostItem(PostMediaKind.PHOTO, "photo"))
        val receipt = TelegramReceiptCodec.post(items)
        assertEquals(CachedMedia.Post(items), TelegramReceiptCodec.decode(OutputType.POST, receipt))
        assertEquals(CachedMedia.Post(items), TelegramReceiptCodec.decode(OutputType.VIDEO, receipt))
        kotlin.test.assertNotEquals(ResultKeyFactory.forNewJob("key", OutputType.VIDEO), ResultKeyFactory.forNewJob("key", OutputType.POST))
    }

    @Test
    fun decodesTheLegacyMediaKindWithoutUsingCachedPostText() {
        val jobs = mockk<DownloadJobService>()
        val cache = TelegramResultCache(jobs)
        every { jobs.findCachedFileId("unchanged") } returns "stored"
        for ((output, kind) in listOf(OutputType.VIDEO to TelegramMediaKind.VIDEO, OutputType.AUDIO to TelegramMediaKind.AUDIO, OutputType.COVER to TelegramMediaKind.DOCUMENT)) {
            val request = DownloadRequestMapper.single(DownloadJob(outputType = output, cacheKey = "unchanged", sourcePostText = "Current text"))
            assertEquals(CachedMedia.Single(kind, "stored"), cache.find(request))
            assertEquals("Current text", request.postText)
        }
        verify(exactly = 3) { jobs.findCachedFileId("unchanged") }
    }

    @Test
    fun retainsAlbumOrderAndSeparatesPlaylistCompletionFromMedia() {
        val receipt = "photo-group:[\"second\",\"first\"]"
        val media = TelegramReceiptCodec.decode(OutputType.IMAGES, receipt) as CachedMedia.Photos
        assertEquals(listOf("second", "first"), media.fileIds)
        assertEquals(receipt, TelegramReceiptCodec.photos(media.fileIds))
        assertEquals("playlist:2", TelegramReceiptCodec.playlist(PlaylistCompletion.AudioMessages(2)))
        assertEquals("archive", TelegramReceiptCodec.playlist(PlaylistCompletion.Archive("archive")))
    }

    @Test
    fun playlistTrackIdentityMatchesTheSingleAudioMenuIdentity() {
        val singleKey = ResultKeyFactory.choice(
            ResultKeyFactory.source("youtube:video:track", OutputType.AUDIO, "youtube_audio"),
            ResultKeyFactory.audioSuffix("96K"),
        )
        assertEquals("youtube:video:track:audio:youtube_audio:audio:96K", singleKey)
        assertEquals(singleKey, ResultKeyFactory.playlistAudioEntry("track"))
        assertEquals("youtube:playlist:PL:audio:96:playlist_first:zip", ResultKeyFactory.playlistZip(ResultKeyFactory.playlist("PL", "playlist_first")))
    }
}
