package com.nkudrin713.kradnik.download.domain

import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.localization.BotLanguage
import java.time.Instant

/** Persistent request and queue state. Audio metadata is local to one processing call; full-post text survives retries. */
class DownloadJob(
    var id: Long? = null,
    var telegramUserId: Long = 0,
    var telegramChatId: Long = 0,
    var telegramUpdateId: Int? = null,
    var retryOfJobId: Long? = null,
    var telegramRequestMessageId: Int? = null,
    var language: BotLanguage = BotLanguage.EN,
    var originalUrl: String = "",
    var normalizedUrl: String = "",
    var cacheKey: String = "",
    var outputType: OutputType = OutputType.VIDEO,
    var platform: DownloadPlatform = DownloadPlatform.YOUTUBE,
    var status: DownloadJobStatus = DownloadJobStatus.QUEUED,
    var sourcePostText: String? = null,
    var workloadType: DownloadWorkloadType = DownloadWorkloadType.SINGLE,
    var playlistDeliveryMode: PlaylistDeliveryMode = PlaylistDeliveryMode.AUDIO_MESSAGES,
    var playlistTitle: String? = null,
    var playlistEntries: List<PlaylistAudioEntry> = emptyList(),
    var playlistResults: List<PlaylistAudioResult> = emptyList(),
    var downloadPreset: String = "",
    var selectedFormat: String = "",
    var downloadExtraArgs: List<String> = emptyList(),
    var telegramFileId: String? = null,
    var telegramStatusMessageId: Int? = null,
    var telegramInlineMessageId: String? = null,
    var errorMessage: String? = null,
    var createdAt: Instant? = null,
    var updatedAt: Instant? = null,
    var completedAt: Instant? = null,
    var startedAt: Instant? = null,
) {
    fun requiredId(): Long = requireNotNull(id)
}
