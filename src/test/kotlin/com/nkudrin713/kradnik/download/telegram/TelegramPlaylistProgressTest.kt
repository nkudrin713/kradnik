package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.playlist.PlaylistCompletion
import com.nkudrin713.kradnik.download.playlist.PlaylistDeliveryResult
import com.nkudrin713.kradnik.download.processing.PlaylistProgress
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.TelegramSender
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class TelegramPlaylistProgressTest {
    @Test
    fun throttlesEditsWarnsOnceAndDoesNotPublishAfterCancellation() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val jobs = mockk<DownloadJobService>()
        every { jobs.isProcessing(1) } returns true
        val job = DownloadJob(id = 1, telegramChatId = 10, telegramStatusMessageId = 20, workloadType = DownloadWorkloadType.PLAYLIST_AUDIO)
        val progress = TelegramJobProgress(sender, telegramMessages(), jobs).forJob(job)
        val state = PlaylistProgress(44, 0, 0, listOf(PlaylistAudioEntry(1, "id", "url", "Long recording", 7200)), true)
        repeat(3) { progress.playlist(state) }
        verify(exactly = 1) { sender.sendMessage(10, any()) }
        verify(exactly = 1) { sender.editPlaylistProgress(any(), any(), 1, job.language, any()) }
        every { jobs.isProcessing(1) } returns false
        progress.playlist(state)
        verify(exactly = 1) { sender.editPlaylistProgress(any(), any(), 1, job.language, any()) }
    }

    @Test
    fun showsPlaybackHintOnlyForMultipleDeliveredAudios() {
        val messages = telegramMessages()
        for (mode in PlaylistDeliveryMode.entries) {
            for (count in listOf(1, 2)) {
                val sender = mockk<TelegramSender>(relaxed = true)
                val job = DownloadJob(id = 1, telegramChatId = 10, playlistDeliveryMode = mode)
                val completion = if (mode == PlaylistDeliveryMode.AUDIO_MESSAGES) {
                    PlaylistCompletion.AudioMessages(count)
                } else {
                    PlaylistCompletion.Archive("zip")
                }
                TelegramJobProgress(sender, messages, mockk()).playlistSummary(job, PlaylistDeliveryResult(count, 0, completion))
                val expected = if (mode == PlaylistDeliveryMode.AUDIO_MESSAGES && count > 1) 1 else 0
                verify(exactly = expected) { sender.sendMessage(10, messages.text(job.language, TelegramMessage.PLAYLIST_PLAYBACK_HINT)) }
            }
        }
    }
}
