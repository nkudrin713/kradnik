package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.DownloadEngine
import com.nkudrin713.kradnik.download.delivery.MessageAddress
import com.nkudrin713.kradnik.download.delivery.PlaylistDeliveryResult
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.domain.PlaylistItemFailure
import com.nkudrin713.kradnik.download.playlist.PlaylistEmptyException
import com.nkudrin713.kradnik.download.progress.DownloadPhase
import com.nkudrin713.kradnik.download.progress.PlaylistProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.download.testPlatformResolver
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class TelegramPlaylistProgressTest {
    @Test
    fun appendsWarningToStatusThrottlesEditsAndDoesNotPublishAfterCancellation() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val jobs = mockk<DownloadJobService>()
        every { jobs.isProcessing(1) } returns true
        val job = DownloadJob(id = 1, telegramChatId = 10, telegramStatusMessageId = 20, workloadType = DownloadWorkloadType.PLAYLIST_AUDIO)
        val progress = TelegramJobProgress(sender, telegramMessages(), jobs, mockk(relaxed = true), DownloadEngine(testPlatformResolver())).forJob(job)
        val state = PlaylistProgress(44, 0, 0, listOf(PlaylistAudioEntry(1, "id", "url", "Long recording", 7200)), true)
        repeat(3) { progress.playlist(state) }
        val warning = telegramMessages().text(job.language, MessageKey.PLAYLIST_LONG_WARNING)
        verify(exactly = 0) { sender.sendMessage(any(), any()) }
        verify(exactly = 1) { sender.editPlaylistProgress(MessageAddress.Chat(10, 20), match { it.endsWith("\n\n$warning") && it.contains("Long recording") }, 1, job.language, any()) }
        every { jobs.isProcessing(1) } returns false
        progress.playlist(state)
        verify(exactly = 1) { sender.editPlaylistProgress(any(), any(), 1, job.language, any()) }
    }

    @Test
    fun addsNewWarningImmediatelyAndRetainsItInLaterPhaseUpdatesInEveryLanguage() {
        val messages = telegramMessages()
        for (language in BotLanguage.entries) {
            val sender = mockk<TelegramSender>(relaxed = true)
            val jobs = mockk<DownloadJobService>()
            every { jobs.isProcessing(1) } returns true
            val job = DownloadJob(id = 1, telegramChatId = 10, telegramStatusMessageId = 20, workloadType = DownloadWorkloadType.PLAYLIST_AUDIO, language = language)
            val progress = TelegramJobProgress(sender, messages, jobs, mockk(relaxed = true), DownloadEngine(testPlatformResolver())).forJob(job)
            val state = PlaylistProgress(1, 0, 0, emptyList())
            progress.playlist(state)
            progress.playlist(state.copy(longWait = true))
            val warning = messages.text(language, MessageKey.PLAYLIST_LONG_ITEM_WARNING)
            val status = messages.text(language, MessageKey.PLAYLIST_PROGRESS, 0, 1, 0, 0)
            verify(exactly = 1) { sender.editPlaylistProgress(MessageAddress.Chat(10, 20), "$status\n\n$warning", 1, language, any()) }
            progress.update(DownloadPhase.PACKING)
            val packing = messages.text(language, MessageKey.STATUS_PACKING)
            verify(exactly = 1) { sender.editPlaylistProgress(MessageAddress.Chat(10, 20), "$packing\n\n$warning", 1, language, any()) }
            verify(exactly = 0) { sender.sendMessage(any(), any()) }
        }
    }

    @Test
    fun showsPlaybackHintOnlyForMultipleDeliveredAudios() {
        val messages = telegramMessages()
        for (mode in PlaylistDeliveryMode.entries) {
            for (count in listOf(1, 2)) {
                val sender = mockk<TelegramSender>(relaxed = true)
                val job = DownloadJob(id = 1, telegramChatId = 10, playlistDeliveryMode = mode)
                val completion = if (mode == PlaylistDeliveryMode.AUDIO_MESSAGES) {
                    "playlist:$count"
                } else {
                    "zip"
                }
                TelegramJobProgress(sender, messages, mockk(), mockk(relaxed = true), DownloadEngine(testPlatformResolver())).playlistSummary(job, PlaylistDeliveryResult(count, 0, completion))
                val expected = if (mode == PlaylistDeliveryMode.AUDIO_MESSAGES && count > 1) 1 else 0
                verify(exactly = expected) { sender.sendMessage(10, messages.text(job.language, MessageKey.PLAYLIST_PLAYBACK_HINT)) }
            }
        }
    }

    @Test
    fun keepsPlaylistStatusAndEditsItToFinalCountInEveryLanguage() {
        val messages = telegramMessages()
        for (language in BotLanguage.entries) {
            val sender = mockk<TelegramSender>(relaxed = true)
            val job = DownloadJob(
                id = 1,
                telegramChatId = 10,
                telegramStatusMessageId = 20,
                workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
                language = language,
                playlistEntries = listOf(PlaylistAudioEntry(1, "a", "url", "First", null)),
            )
            val progress = TelegramJobProgress(sender, messages, mockk(), mockk(relaxed = true), DownloadEngine(testPlatformResolver()))

            progress.completed(job)
            progress.playlistSummary(job, PlaylistDeliveryResult(1, 0, "playlist:1"))

            verify(exactly = 0) { sender.deleteMessage(any(), any()) }
            verify(exactly = 1) {
                sender.editFinalMessage(
                    MessageAddress.Chat(10, 20),
                    messages.text(language, MessageKey.PLAYLIST_COMPLETED, 1, 1),
                )
            }
        }
    }

    @Test
    fun putsPartialFailureReportInExistingStatus() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val messages = telegramMessages()
        val entries = listOf(
            PlaylistAudioEntry(1, "a", "url", "First", null),
            PlaylistAudioEntry(2, "b", "url", "Second", null),
        )
        val job = DownloadJob(
            id = 1,
            telegramChatId = 10,
            telegramStatusMessageId = 20,
            workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
            playlistEntries = entries,
        )
        val failure = PlaylistAudioResult(2, failure = PlaylistItemFailure.TIMEOUT)

        TelegramJobProgress(sender, messages, mockk(), mockk(relaxed = true), DownloadEngine(testPlatformResolver())).playlistSummary(job, PlaylistDeliveryResult(1, 1, "playlist:1", listOf(failure)))

        verify(exactly = 1) {
            sender.editPlaylistReport(
                MessageAddress.Chat(10, 20),
                match { it.contains("1 of 2") && it.contains("2. <a") && it.contains("Processing timed out") },
                1,
                job.language,
                1,
                false,
                any(),
            )
        }
        verify(exactly = 0) { sender.sendHtmlMessage(any(), any()) }
    }

    @Test
    fun putsAllFailedReportInExistingStatus() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val messages = telegramMessages()
        val entry = PlaylistAudioEntry(1, "a", "url", "First", null)
        val job = DownloadJob(
            id = 1,
            telegramChatId = 10,
            telegramStatusMessageId = 20,
            workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
            playlistEntries = listOf(entry),
        )

        TelegramJobProgress(sender, messages, mockk(), mockk(relaxed = true), DownloadEngine(testPlatformResolver())).playlistFailure(
            job,
            PlaylistEmptyException(listOf(PlaylistAudioResult(1, failure = PlaylistItemFailure.TIMEOUT))),
        )

        verify(exactly = 1) {
            sender.editPlaylistReport(
                MessageAddress.Chat(10, 20),
                match { it.contains(messages.text(job.language, MessageKey.PLAYLIST_ALL_FAILED)) && it.contains("1. <a") },
                1,
                job.language,
                1,
                false,
                any(),
            )
        }
        verify(exactly = 0) { sender.sendHtmlMessage(any(), any()) }
    }

    @Test
    fun offersWholeLinkRetryWithoutPerTrackRetryForPermanentSourceFailure() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val messages = telegramMessages()
        val job = DownloadJob(
            id = 1,
            telegramChatId = 10,
            telegramStatusMessageId = 20,
            workloadType = DownloadWorkloadType.PLAYLIST_AUDIO,
            playlistEntries = listOf(PlaylistAudioEntry(1, "a", "url", "First", null)),
        )

        TelegramJobProgress(sender, messages, mockk(), mockk(relaxed = true), DownloadEngine(testPlatformResolver())).playlistFailure(
            job,
            PlaylistEmptyException(listOf(PlaylistAudioResult(1, failure = PlaylistItemFailure.GEO_BLOCKED))),
        )

        verify(exactly = 1) {
            sender.editPlaylistReport(MessageAddress.Chat(10, 20), match { it.contains("1. <a") }, 1, job.language, 0, false, any())
        }
        verify(exactly = 0) { sender.editFinalMessage(any(), any(), any()) }
    }
}
