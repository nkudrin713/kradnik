package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.admin.AdminRuntime
import com.nkudrin713.kradnik.download.choice.DownloadChoicePlanner
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSession
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionService
import com.nkudrin713.kradnik.download.delivery.MessageAddress
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDisabledException
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadChoiceCoordinatorTest {
    @Test
    fun sourceFailuresDuringPlanningShowActionableLocalizedMessages() = runTest {
        val planner = mockk<DownloadChoicePlanner>()
        val sessions = mockk<DownloadChoiceSessionService>()
        every { sessions.createRetry(any()) } returns DownloadChoiceSession()
        val sender = mockk<TelegramSender>(relaxed = true)
        val messages = telegramMessages()
        val coordinator = DownloadChoiceCoordinator(planner, sessions, sender, messages)
        val statuses = mapOf(
            DownloadFailureReason.FORMAT_UNAVAILABLE to MessageKey.STATUS_FORMAT_UNAVAILABLE,
            DownloadFailureReason.AUTHENTICATION_REQUIRED to MessageKey.STATUS_AUTHENTICATION_REQUIRED,
            DownloadFailureReason.SOURCE_UNAVAILABLE to MessageKey.STATUS_SOURCE_UNAVAILABLE,
            DownloadFailureReason.SOURCE_RATE_LIMITED to MessageKey.STATUS_SOURCE_RATE_LIMITED,
            DownloadFailureReason.SOURCE_REQUEST_FAILED to MessageKey.STATUS_SOURCE_REQUEST_FAILED,
            DownloadFailureReason.SOURCE_TIMEOUT to MessageKey.STATUS_SOURCE_TIMEOUT,
            DownloadFailureReason.SOURCE_FAILED to MessageKey.STATUS_ERROR,
        )
        try {
            for (language in BotLanguage.entries) {
                for ((reason, key) in statuses) {
                    coEvery { planner.plan(any(), any()) } throws DownloadFailure(reason, "Private diagnostic")
                    coordinator.prepareAsync(
                        PrepareDownloadChoiceCommand(1, 2, 3, 4, "https://youtu.be/id", language),
                        MessageAddress.Inline("inline"),
                    )
                    verify { sender.editFailure(MessageAddress.Inline("inline"), messages.text(language, key), any(), language, null) }
                }
            }
            verify(exactly = 0) { sessions.create(any()) }
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun disabledServiceShowsLocalizedMessageWithoutCountingMetadataFailure() = runTest {
        val planner = mockk<DownloadChoicePlanner>()
        val sessions = mockk<DownloadChoiceSessionService>()
        every { sessions.createRetry(any()) } returns DownloadChoiceSession()
        val sender = mockk<TelegramSender>(relaxed = true)
        val runtime = AdminRuntime(true)
        val coordinator = DownloadChoiceCoordinator(planner, sessions, sender, telegramMessages(), runtime)
        coEvery { planner.plan(any(), any()) } throws PlatformDisabledException(DownloadPlatform.VK)
        try {
            coordinator.prepareAsync(
                PrepareDownloadChoiceCommand(1, 2, 3, 4, "https://vk.com/video1_2", BotLanguage.RU),
                MessageAddress.Chat(2, 5),
            )

            verify { sender.editFailure(MessageAddress.Chat(2, 5), telegramMessages().text(BotLanguage.RU, MessageKey.ERROR_PLATFORM_DISABLED, "VK"), any(), BotLanguage.RU, null) }
            verify(exactly = 0) { sessions.create(any()) }
            assertEquals(0L, runtime.snapshot().errors.first().counts["METADATA"])
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun boundsPendingMetadataRequestsAndCancelsActiveRequestsOnShutdown() {
        val planner = mockk<DownloadChoicePlanner>()
        val sessions = mockk<DownloadChoiceSessionService>()
        every { sessions.createRetry(any()) } returns DownloadChoiceSession()
        val sender = mockk<TelegramSender>(relaxed = true)
        val messages = mockk<Messages>(relaxed = true)
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
        every { messages.text(BotLanguage.EN, MessageKey.ERROR_CHOICE_PREPARATION) } returns "Preparation failed"
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
            verify(exactly = 1) { sender.editFailure(any(), "Preparation failed", any(), any(), null) }
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
