package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.admin.AdminRuntime
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanner
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadChoiceCoordinatorTest {
    @Test
    fun boundsPendingMetadataRequestsAndCancelsActiveRequestsOnShutdown() {
        val planner = mockk<DownloadChoicePlanner>()
        val sessions = mockk<DownloadChoiceSessionService>()
        val sender = mockk<TelegramSender>(relaxed = true)
        val messages = mockk<TelegramMessages>(relaxed = true)
        val started = CountDownLatch(2)
        val stopped = CountDownLatch(2)
        val active = AtomicInteger()
        coEvery { planner.plan(any(), any()) } coAnswers {
            active.incrementAndGet()
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                active.decrementAndGet()
                stopped.countDown()
            }
        }
        every { messages.text(BotLanguage.EN, TelegramMessage.ERROR_CHOICE_PREPARATION) } returns "Preparation failed"
        val runtime = AdminRuntime(true)
        val coordinator = DownloadChoiceCoordinator(planner, sessions, sender, messages, runtime)
        fun request(id: Int) = PrepareDownloadChoiceCommand(
            telegramUserId = 1,
            telegramChatId = 2,
            telegramUpdateId = id,
            telegramRequestMessageId = id,
            url = "https://youtu.be/id",
            language = BotLanguage.EN,
        )
        try {
            coordinator.prepare(request(1))
            coordinator.prepare(request(2))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            for (id in 3..34) coordinator.prepare(request(id))
            coordinator.prepare(request(35))
            assertEquals(2, active.get())
            assertEquals(2, runtime.snapshot().workers.count { it.state == "BUSY" })
            assertEquals(32, runtime.snapshot().metadataQueued)
            assertEquals(1L, runtime.snapshot().errors.first().counts["METADATA_REJECTED"])
            verify(exactly = 1) { sender.editMessage(any(), "Preparation failed") }
        } finally {
            coordinator.shutdown()
        }
        assertTrue(stopped.await(10, TimeUnit.SECONDS))
        assertEquals(0, active.get())
        assertEquals(0, runtime.snapshot().metadataQueued)
        assertTrue(runtime.snapshot().workers.all { it.state == "STOPPED" })
        assertEquals(0L, runtime.snapshot().errors.first().counts["METADATA"])
    }
}
