package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.telegram.handler.TelegramUpdateHandler
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramPollingServiceTest {
    private val bot: TelegramBot = mockk()
    private val updateHandler: TelegramUpdateHandler = mockk()
    private val service = TelegramPollingService(bot, updateHandler)

    @Test
    fun confirmsOnlyUpdatesBeforeFailure() {
        val listener = captureListener()
        val first = update(10)
        val second = update(11)
        every { updateHandler.handle(first) } just runs
        every { updateHandler.handle(second) } throws IllegalStateException("database unavailable")

        val confirmedUpdateId = listener.process(listOf(first, second))

        assertEquals(10, confirmedUpdateId)
    }

    @Test
    fun confirmsNoneWhenFirstUpdateFails() {
        val listener = captureListener()
        val update = update(10)
        every { updateHandler.handle(update) } throws IllegalStateException("database unavailable")

        val confirmedUpdateId = listener.process(listOf(update))

        assertEquals(UpdatesListener.CONFIRMED_UPDATES_NONE, confirmedUpdateId)
    }

    @Test
    fun confirmsLastSuccessfullyHandledUpdate() {
        val listener = captureListener()
        val first = update(10)
        val second = update(11)
        every { updateHandler.handle(any()) } just runs

        val confirmedUpdateId = listener.process(listOf(first, second))

        assertEquals(11, confirmedUpdateId)
    }

    private fun captureListener(): UpdatesListener {
        val listener = slot<UpdatesListener>()
        every { bot.setUpdatesListener(capture(listener)) } just runs
        service.start()
        return listener.captured
    }

    private fun update(id: Int): Update {
        return mockk {
            every { updateId() } returns id
        }
    }
}
