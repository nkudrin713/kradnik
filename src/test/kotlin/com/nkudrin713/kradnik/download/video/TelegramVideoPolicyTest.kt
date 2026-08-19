package com.nkudrin713.kradnik.download.video

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramVideoPolicyTest {
    private val policy = TelegramVideoPolicy()

    @Test
    fun acceptsCompatibleVideo() {
        val result = policy.evaluate(
            metadata = compatibleMetadata(),
            sizeBytes = TelegramUploadLimits.MAX_UPLOAD_BYTES,
        )

        assertEquals(TelegramVideoPolicyDecision.Accepted, result)
    }

    @Test
    fun transcodesVp9Video() {
        val result = assertIs<TelegramVideoPolicyDecision.Transcode>(
            policy.evaluate(
                metadata = compatibleMetadata().copy(videoCodec = "vp9", codecTag = "vp09"),
                sizeBytes = 1_000,
            )
        )

        assertEquals(setOf(TelegramVideoIssue.VIDEO_CODEC), result.issues)
    }

    @Test
    fun transcodesIncompatibleContainerPixelFormatAndAudio() {
        val result = assertIs<TelegramVideoPolicyDecision.Transcode>(
            policy.evaluate(
                metadata = compatibleMetadata().copy(
                    containerFormat = "matroska,webm",
                    pixelFormat = "yuv420p10le",
                    audioCodec = "opus",
                ),
                sizeBytes = 1_000,
            )
        )

        assertEquals(
            setOf(
                TelegramVideoIssue.CONTAINER,
                TelegramVideoIssue.PIXEL_FORMAT,
                TelegramVideoIssue.AUDIO_CODEC,
            ),
            result.issues,
        )
    }

    @Test
    fun acceptsVideoWithoutAudio() {
        val result = policy.evaluate(
            metadata = compatibleMetadata().copy(audioCodec = null),
            sizeBytes = 1_000,
        )

        assertEquals(TelegramVideoPolicyDecision.Accepted, result)
    }

    @Test
    fun transcodesLargeVerticalVideo() {
        val result = assertIs<TelegramVideoPolicyDecision.Transcode>(
            policy.evaluate(
                metadata = compatibleMetadata(),
                sizeBytes = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
            )
        )

        assertEquals(setOf(TelegramVideoIssue.FILE_SIZE), result.issues)
    }

    @Test
    fun rejectsLargeHorizontalVideo() {
        val result = policy.evaluate(
            metadata = compatibleMetadata().copy(width = 1920, height = 1080),
            sizeBytes = TelegramUploadLimits.MAX_UPLOAD_BYTES + 1,
        )

        assertEquals(TelegramVideoPolicyDecision.RejectedTooLarge, result)
    }

    @Test
    fun versionsVideoCacheKey() {
        assertEquals(
            "instagram:reel:video:telegram-video-h264-v1",
            TelegramVideoPolicy.versionCacheKey("instagram:reel:video"),
        )
    }

    private fun compatibleMetadata(): VideoMetadata {
        return VideoMetadata(
            width = 1080,
            height = 1920,
            sampleAspectRatio = "1:1",
            displayAspectRatio = "9:16",
            containerFormat = "mov,mp4,m4a,3gp,3g2,mj2",
            videoCodec = "h264",
            audioCodec = "aac",
            codecTag = "avc1",
            pixelFormat = "yuv420p",
        )
    }
}
