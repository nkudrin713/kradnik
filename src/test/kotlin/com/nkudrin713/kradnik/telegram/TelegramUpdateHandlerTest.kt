package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.service.CreateDownloadTaskCommand
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import com.nkudrin713.kradnik.settings.DownloadMode
import com.nkudrin713.kradnik.settings.DownloadSettingsDto
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramUpdateHandlerTest {
    private val downloadTaskService: DownloadTaskService = mock()
    private val downloadSettingsService: DownloadSettingsService = mock()
    private val urlExtractor = UrlExtractor()

    private val handler = TelegramUpdateHandler(
        downloadTaskService = downloadTaskService,
        downloadSettingsService = downloadSettingsService,
        urlExtractor = urlExtractor,
    )

    @Test
    fun `creates download task from url`() {
        whenever(downloadSettingsService.getMode(100)).thenReturn(DownloadMode.AUDIO)

        handler.handle(update(text = "https://example.com/video", chatId = 100, userId = 200))

        val captor = argumentCaptor<CreateDownloadTaskCommand>()
        verify(downloadTaskService).createTask(captor.capture())

        assertEquals(200, captor.firstValue.telegramUserId)
        assertEquals(100, captor.firstValue.telegramChatId)
        assertEquals("https://example.com/video", captor.firstValue.originalUrl)
        assertEquals("https://example.com/video", captor.firstValue.normalizedUrl)
        assertEquals(DownloadOutputType.AUDIO, captor.firstValue.outputType)
    }

    @Test
    fun `sets audio mode`() {
        handler.handle(update(text = "/audio", chatId = 100, userId = 200))

        val captor = argumentCaptor<DownloadSettingsDto>()
        verify(downloadSettingsService).setMode(captor.capture())

        assertEquals(100, captor.firstValue.chatId)
        assertEquals("AUDIO", captor.firstValue.mode)
    }

    @Test
    fun `ignores message without url`() {
        handler.handle(update(text = "hello", chatId = 100, userId = 200))

        verify(downloadTaskService, never()).createTask(org.mockito.kotlin.any())
    }

    private fun update(text: String, chatId: Long, userId: Long): Update {
        val user: User = mock()
        whenever(user.id).thenReturn(userId)

        val message: Message = mock()
        whenever(message.text).thenReturn(text)
        whenever(message.chatId).thenReturn(chatId)
        whenever(message.from).thenReturn(user)

        val update: Update = mock()
        whenever(update.message).thenReturn(message)

        return update
    }
}
