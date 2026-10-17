package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.persistence.BotLanguageConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

/** Persistent request and queue state. Audio metadata is local to one processing call; full-post text survives retries. */
@Entity(name = "DownloadJob")
@Table(name = "download_jobs")
class DownloadJobEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "telegram_user_id", nullable = false)
    var telegramUserId: Long = 0,

    @Column(name = "telegram_chat_id", nullable = false)
    var telegramChatId: Long = 0,

    @Column(name = "telegram_update_id")
    var telegramUpdateId: Int? = null,

    @Column(name = "retry_of_job_id")
    var retryOfJobId: Long? = null,

    @Column(name = "telegram_request_message_id")
    var telegramRequestMessageId: Int? = null,

    @Convert(converter = BotLanguageConverter::class)
    @Column(nullable = false)
    var language: BotLanguage = BotLanguage.EN,

    @Column(name = "original_url", nullable = false)
    var originalUrl: String = "",

    @Column(name = "normalized_url", nullable = false)
    var normalizedUrl: String = "",

    @Column(name = "cache_key", nullable = false)
    var cacheKey: String = "",

    @Convert(converter = DownloadOutputTypeConverter::class)
    @Column(name = "output_type", nullable = false)
    var outputType: OutputType = OutputType.VIDEO,

    @Convert(converter = DownloadPlatformConverter::class)
    @Column(name = "platform", nullable = false)
    var platform: DownloadPlatform = DownloadPlatform.YOUTUBE,

    @Convert(converter = DownloadJobStatusConverter::class)
    @Column(nullable = false)
    var status: DownloadJobStatus = DownloadJobStatus.QUEUED,

    @Column(name = "source_post_text", columnDefinition = "TEXT")
    var sourcePostText: String? = null,

    @Convert(converter = DownloadWorkloadTypeConverter::class)
    @Column(name = "workload_type", nullable = false)
    var workloadType: DownloadWorkloadType = DownloadWorkloadType.SINGLE,

    @Enumerated(EnumType.STRING)
    @Column(name = "playlist_delivery_mode", nullable = false)
    var playlistDeliveryMode: PlaylistDeliveryMode = PlaylistDeliveryMode.AUDIO_MESSAGES,

    @Column(name = "playlist_title", columnDefinition = "TEXT")
    var playlistTitle: String? = null,

    @Convert(converter = PlaylistAudioEntriesJsonConverter::class)
    @Column(name = "playlist_entries_json", nullable = false, columnDefinition = "TEXT")
    var playlistEntries: List<PlaylistAudioEntry> = emptyList(),

    @Convert(converter = PlaylistAudioResultsJsonConverter::class)
    @Column(name = "playlist_results_json", nullable = false, columnDefinition = "TEXT")
    var playlistResults: List<PlaylistAudioResult> = emptyList(),

    @Column(name = "download_preset", nullable = false)
    var downloadPreset: String = "",

    @Column(name = "selected_format", nullable = false)
    var selectedFormat: String = "",

    @Convert(converter = StringListJsonConverter::class)
    @Column(name = "download_extra_args", nullable = false)
    var downloadExtraArgs: List<String> = emptyList(),

    @Column(name = "telegram_file_id")
    var telegramFileId: String? = null,

    @Column(name = "telegram_status_message_id")
    var telegramStatusMessageId: Int? = null,

    @Column(name = "telegram_inline_message_id")
    var telegramInlineMessageId: String? = null,

    @Column(name = "error_message")
    var errorMessage: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "started_at")
    var startedAt: Instant? = null,
) {
    fun requiredId(): Long = requireNotNull(id)
    fun toJob(): DownloadJob = DownloadJob(
        id = id,
        telegramUserId = telegramUserId,
        telegramChatId = telegramChatId,
        telegramUpdateId = telegramUpdateId,
        retryOfJobId = retryOfJobId,
        telegramRequestMessageId = telegramRequestMessageId,
        language = language,
        originalUrl = originalUrl,
        normalizedUrl = normalizedUrl,
        cacheKey = cacheKey,
        outputType = outputType,
        platform = platform,
        status = status,
        sourcePostText = sourcePostText,
        workloadType = workloadType,
        playlistDeliveryMode = playlistDeliveryMode,
        playlistTitle = playlistTitle,
        playlistEntries = playlistEntries,
        playlistResults = playlistResults,
        downloadPreset = downloadPreset,
        selectedFormat = selectedFormat,
        downloadExtraArgs = downloadExtraArgs,
        telegramFileId = telegramFileId,
        telegramStatusMessageId = telegramStatusMessageId,
        telegramInlineMessageId = telegramInlineMessageId,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        startedAt = startedAt,
    )

    companion object {
        fun from(value: DownloadJob): DownloadJobEntity = DownloadJobEntity(
            id = value.id,
            telegramUserId = value.telegramUserId,
            telegramChatId = value.telegramChatId,
            telegramUpdateId = value.telegramUpdateId,
            retryOfJobId = value.retryOfJobId,
            telegramRequestMessageId = value.telegramRequestMessageId,
            language = value.language,
            originalUrl = value.originalUrl,
            normalizedUrl = value.normalizedUrl,
            cacheKey = value.cacheKey,
            outputType = value.outputType,
            platform = value.platform,
            status = value.status,
            sourcePostText = value.sourcePostText,
            workloadType = value.workloadType,
            playlistDeliveryMode = value.playlistDeliveryMode,
            playlistTitle = value.playlistTitle,
            playlistEntries = value.playlistEntries,
            playlistResults = value.playlistResults,
            downloadPreset = value.downloadPreset,
            selectedFormat = value.selectedFormat,
            downloadExtraArgs = value.downloadExtraArgs,
            telegramFileId = value.telegramFileId,
            telegramStatusMessageId = value.telegramStatusMessageId,
            telegramInlineMessageId = value.telegramInlineMessageId,
            errorMessage = value.errorMessage,
            createdAt = value.createdAt,
            updatedAt = value.updatedAt,
            completedAt = value.completedAt,
            startedAt = value.startedAt,
        )
    }
}
