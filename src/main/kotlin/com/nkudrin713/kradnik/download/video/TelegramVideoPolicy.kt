package com.nkudrin713.kradnik.download.video

import com.nkudrin713.kradnik.download.limit.TelegramUploadLimits
import org.springframework.stereotype.Component

@Component
class TelegramVideoPolicy {
    fun evaluate(
        metadata: VideoMetadata,
        sizeBytes: Long,
    ): TelegramVideoPolicyDecision {
        if (sizeBytes > TelegramUploadLimits.MAX_UPLOAD_BYTES && !metadata.isVertical) {
            return TelegramVideoPolicyDecision.RejectedTooLarge
        }

        val issues = buildSet {
            if (!metadata.isMp4Container) {
                add(TelegramVideoIssue.CONTAINER)
            }
            if (metadata.videoCodec != H264_CODEC) {
                add(TelegramVideoIssue.VIDEO_CODEC)
            }
            if (metadata.pixelFormat != YUV420P_PIXEL_FORMAT) {
                add(TelegramVideoIssue.PIXEL_FORMAT)
            }
            if (metadata.audioCodec != null && metadata.audioCodec != AAC_CODEC) {
                add(TelegramVideoIssue.AUDIO_CODEC)
            }
            if (sizeBytes > TelegramUploadLimits.MAX_UPLOAD_BYTES) {
                add(TelegramVideoIssue.FILE_SIZE)
            }
        }

        return if (issues.isEmpty()) {
            TelegramVideoPolicyDecision.Accepted
        } else {
            TelegramVideoPolicyDecision.Transcode(issues)
        }
    }

    companion object {
        private const val DELIVERY_PROFILE = "telegram-video-h264-v1"
        private const val H264_CODEC = "h264"
        private const val AAC_CODEC = "aac"
        private const val YUV420P_PIXEL_FORMAT = "yuv420p"

        fun versionCacheKey(cacheKey: String): String = "$cacheKey:$DELIVERY_PROFILE"
    }
}

enum class TelegramVideoIssue {
    CONTAINER,
    VIDEO_CODEC,
    PIXEL_FORMAT,
    AUDIO_CODEC,
    FILE_SIZE,
}

sealed interface TelegramVideoPolicyDecision {
    data object Accepted : TelegramVideoPolicyDecision

    data class Transcode(
        val issues: Set<TelegramVideoIssue>,
    ) : TelegramVideoPolicyDecision

    data object RejectedTooLarge : TelegramVideoPolicyDecision
}
