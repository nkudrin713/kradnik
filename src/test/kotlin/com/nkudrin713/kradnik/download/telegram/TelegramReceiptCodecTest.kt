package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.identity.ResultKeyFactory
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.source.PlaylistSourceAdapter
import com.nkudrin713.kradnik.download.testPlatformResolver
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramReceiptCodecTest {
    @Test
    fun storesPostTextWithMediaAndSupportsTextOnlyResults() {
        val items = listOf(CachedPostItem(PostMediaKind.PHOTO, "photo"))
        for ((media, text) in listOf(items to "Saved caption", items to null, emptyList<CachedPostItem>() to "Text only")) {
            val receipt = TelegramReceiptCodec.post(media, text)
            assertEquals(CachedMedia.Post(media, text, usesLegacyText = false), TelegramReceiptCodec.decode(OutputType.POST, receipt))
        }
    }

    @Test
    fun roundTripsMixedPostReceiptsAndVersionsPostCacheSeparately() {
        val items = listOf(CachedPostItem(PostMediaKind.VIDEO, "video"), CachedPostItem(PostMediaKind.PHOTO, "photo"))
        val receipt = TelegramReceiptCodec.post(items)
        assertEquals(CachedMedia.Post(items), TelegramReceiptCodec.decode(OutputType.POST, receipt))
        assertEquals(CachedMedia.Post(items), TelegramReceiptCodec.decode(OutputType.VIDEO, receipt))
        kotlin.test.assertNotEquals(ResultKeyFactory.forNewJob("key", OutputType.VIDEO), ResultKeyFactory.forNewJob("key", OutputType.POST))
    }

    @Test
    fun decodesTheLegacyMediaKind() {
        for ((output, kind) in listOf(OutputType.VIDEO to TelegramMediaKind.VIDEO, OutputType.AUDIO to TelegramMediaKind.AUDIO, OutputType.COVER to TelegramMediaKind.DOCUMENT)) {
            assertEquals(CachedMedia.Single(kind, "stored"), TelegramReceiptCodec.decode(output, "stored"))
        }
    }

    @Test
    fun retainsAlbumOrderAndSeparatesPlaylistCompletionFromMedia() {
        val receipt = "photo-group:[\"second\",\"first\"]"
        val media = TelegramReceiptCodec.decode(OutputType.IMAGES, receipt) as CachedMedia.Photos
        assertEquals(listOf("second", "first"), media.fileIds)
        assertEquals(receipt, TelegramReceiptCodec.photos(media.fileIds))
        assertEquals("playlist:2", TelegramReceiptCodec.playlist(2))
    }

    @Test
    fun legacyPlaylistTracksShareSingleAudioIdentityButNewPlaylistsUse320() {
        val singleKey = ResultKeyFactory.choice(
            ResultKeyFactory.source("youtube:video:track", OutputType.AUDIO, "youtube_audio"),
            ResultKeyFactory.audioSuffix("96K"),
        )
        assertEquals("youtube:video:track:audio:youtube_audio:audio:96K", singleKey)
        assertEquals(singleKey, testPlatformResolver().adapter(DownloadPlatform.YOUTUBE).let { (it as PlaylistSourceAdapter).playlistItemCacheKey(PlaylistAudioEntry(1, "track", "url", "title", null), null) })
        assertEquals("youtube:playlist:PL:audio:320:cbr:v1:playlist_first:zip", ResultKeyFactory.playlistZip("youtube:playlist:PL:audio:320:cbr:v1:playlist_first"))
    }
}
