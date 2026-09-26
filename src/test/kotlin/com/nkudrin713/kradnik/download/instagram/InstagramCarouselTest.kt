package com.nkudrin713.kradnik.download.instagram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.PostMediaKind
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.source.SourceRequest
import com.nkudrin713.kradnik.ytdlp.YtDlpMetadataDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class InstagramCarouselTest {
    private val http: InstagramHttpClient = mockk(relaxed = true)
    private val downloader = InstagramEmbedDownloader(http)
    private val request = SourceRequest("https://www.instagram.com/p/TEST/", "https://www.instagram.com/p/TEST/", DownloadPlatform.INSTAGRAM, "best", presetName = "instagram")
    private val mapper = jacksonObjectMapper()

    @Test
    fun preservesMixedOrderRepeatedPhotosAndPostCaption() = runTest {
        coEvery { http.getText(any()) } returns embed(listOf(photo(), video(), photo(), video(null)))
        val post = downloader.prepare(request)
        assertEquals(listOf(PostMediaKind.PHOTO, PostMediaKind.VIDEO, PostMediaKind.PHOTO, PostMediaKind.VIDEO), post.items.map { it.kind })
        assertEquals(post.items[0].uri, post.items[2].uri)
        assertEquals(null, post.items[3].uri)
        assertEquals("Caption <&>\n😀", post.metadata.description)
        assertEquals(null, post.mediaUri)
    }

    @Test
    fun supportsTwentyItemsAndRejectsLargerOrEmptyCarousels() = runTest {
        coEvery { http.getText(any()) } returns embed(List(20) { photo() })
        assertEquals(20, downloader.prepare(request).items.size)
        for (size in listOf(0, 21)) {
            coEvery { http.getText(any()) } returns embed(List(size) { photo() })
            assertFailsWith<InstagramEmbedException> { downloader.prepare(request) }
        }
    }

    @Test
    fun doesNotReplaceMissingOrUntrustedCarouselMediaWithTheCover() = runTest {
        for (invalid in listOf(mapOf("is_video" to false), photo("https://example.com/photo.jpg"))) {
            coEvery { http.getText(any()) } returns embed(listOf(video(), invalid))
            assertFailsWith<InstagramEmbedException> { downloader.prepare(request) }
        }
    }

    @Test
    fun metadataFallbackPreservesMixedItemsAndIgnoresAudioOnlyFormatsForPhotos() {
        val metadata = mapper.readValue(
            """{"description":"Post text","entries":[
                {"thumbnails":[{"url":"https://scontent.cdninstagram.com/photo.jpg"}]},
                {"formats":[{"vcodec":"h264","acodec":"aac"}]},
                {"formats":[{"vcodec":"none","acodec":"aac"}],"thumbnails":[{"url":"https://scontent.cdninstagram.com/photo.jpg"}]}
            ]}""",
            YtDlpMetadataDto::class.java,
        )
        val prepared = assertNotNull(downloader.prepareFromMetadata(request, metadata))
        assertEquals(listOf(PostMediaKind.PHOTO, PostMediaKind.VIDEO, PostMediaKind.PHOTO), prepared.items.map { it.kind })
        assertEquals(null, prepared.items[1].uri)
        assertEquals("Post text", prepared.metadata.description)
    }

    private fun photo(url: String = "https://scontent.cdninstagram.com/photo.jpg") = mapOf("is_video" to false, "display_url" to url)
    private fun video(url: String? = "https://scontent.cdninstagram.com/video.mp4") = mapOf("is_video" to true, "video_url" to url)
    private fun embed(items: List<Map<String, Any?>>): String {
        val context = listOf(
            mapOf(
                "gql_data" to mapOf(
                    "shortcode_media" to mapOf(
                        "is_video" to false,
                        "display_url" to "https://scontent.cdninstagram.com/cover.jpg",
                        "edge_media_to_caption" to mapOf("edges" to listOf(mapOf("node" to mapOf("text" to "Caption <&>\n😀")))),
                        "edge_sidecar_to_children" to mapOf("edges" to items.map { mapOf("node" to it) }),
                    ),
                ),
            ),
        )
        val payload = mapper.writeValueAsString(mapOf("contextJSON" to mapper.writeValueAsString(context)))
        return "<script>[\"init\",[],[$payload]],</script>"
    }
}
