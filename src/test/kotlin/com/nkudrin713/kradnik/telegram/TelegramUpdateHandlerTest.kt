package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.download.pipeline.DownloadTaskUiNotifier
import com.nkudrin713.kradnik.download.service.CreateDownloadTaskCommand
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import com.nkudrin713.kradnik.settings.DownloadMode
import com.nkudrin713.kradnik.settings.DownloadSettings
import com.nkudrin713.kradnik.settings.DownloadSettingsDto
import com.nkudrin713.kradnik.settings.DownloadSettingsService
import com.nkudrin713.kradnik.telegram.ui.BotUiService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramUpdateHandlerTest {
    private val downloadTaskService: DownloadTaskService = mockk()
    private val downloadSettingsService: DownloadSettingsService = mockk()
    private val urlExtractor = UrlExtractor()
    private val botUiService = BotUiService()
    private val telegramClient: TelegramClient = mockk()
    private val downloadTaskUiNotifier: DownloadTaskUiNotifier = mockk(relaxed = true)

    private val handler = TelegramUpdateHandler(
        downloadTaskService = downloadTaskService,
        downloadSettingsService = downloadSettingsService,
        urlExtractor = urlExtractor,
        botUiService = botUiService,
        telegramClient = telegramClient,
        downloadTaskUiNotifier = downloadTaskUiNotifier,
    )

    @Test
    fun `creates download task from url`() {
        every { downloadSettingsService.getMode(100) } returns DownloadMode.AUDIO
        every { downloadTaskService.createTask(any()) } returns DownloadTask(id = 1)

        handler.handle(update(text = "https://example.com/video", chatId = 100, userId = 200))

        val slot = slot<CreateDownloadTaskCommand>()
        verify { downloadTaskService.createTask(capture(slot)) }

        assertEquals(200, slot.captured.telegramUserId)
        assertEquals(100, slot.captured.telegramChatId)
        assertEquals("https://example.com/video", slot.captured.originalUrl)
        assertEquals("https://example.com/video", slot.captured.normalizedUrl)
        assertEquals(DownloadOutputType.AUDIO, slot.captured.outputType)
        verify { downloadTaskUiNotifier.queued(1) }
    }

    @Test
    fun `sets audio mode`() {
        every { downloadSettingsService.setMode(any()) } returns DownloadSettings(chatId = 100, mode = DownloadMode.AUDIO)
        every { telegramClient.execute(any<SendMessage>()) } returns mockk(relaxed = true)

        handler.handle(update(text = "/audio", chatId = 100, userId = 200))

        val slot = slot<DownloadSettingsDto>()
        verify { downloadSettingsService.setMode(capture(slot)) }

        assertEquals(100, slot.captured.chatId)
        assertEquals("AUDIO", slot.captured.mode)
        verify { telegramClient.execute(any<SendMessage>()) }
    }

    @Test
    fun `ignores message without url`() {
        handler.handle(update(text = "hello", chatId = 100, userId = 200))

        verify(exactly = 0) { downloadTaskService.createTask(any()) }
    }

    private fun update(text: String, chatId: Long, userId: Long): Update {
        val user: User = mockk()
        every { user.id } returns userId

        val message: Message = mockk()
        every { message.text } returns text
        every { message.chatId } returns chatId
        every { message.from } returns user

        val update: Update = mockk()
        every { update.message } returns message

        return update
    }
}
