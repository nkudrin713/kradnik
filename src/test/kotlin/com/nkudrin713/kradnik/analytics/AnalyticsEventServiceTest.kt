package com.nkudrin713.kradnik.analytics

import com.nkudrin713.kradnik.app.AppEnvironmentProvider
import com.nkudrin713.kradnik.download.domain.OutputType
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus

class AnalyticsEventServiceTest {
    private val repository: AnalyticsEventRepository = mockk()
    private val transactionManager: PlatformTransactionManager = mockTransactionManager()
    private val service = AnalyticsEventService(
        analyticsEventRepository = repository,
        appEnvironmentProvider = AppEnvironmentProvider("test"),
        transactionManager = transactionManager,
    )

    @Test
    fun `records analytics event in a new transaction`() {
        val eventSlot = slot<AnalyticsEvent>()
        every { repository.save(capture(eventSlot)) } answers { firstArg() }

        service.record(
            RecordAnalyticsEventCommand(
                eventType = AnalyticsEventType.DOWNLOAD_COMPLETED,
                jobId = 42,
                telegramUserId = 100,
                telegramChatId = 200,
                platform = "youtube",
                outputType = OutputType.AUDIO,
                cacheKey = "youtube:video:id:audio:preset",
                sourceDurationSeconds = 120,
                downloadedFileSize = 1_000,
                telegramFileSize = 900,
                success = true,
                properties = mapOf("preset" to "youtube_audio"),
            )
        )

        eventSlot.captured.environment shouldBe "test"
        eventSlot.captured.eventType shouldBe "download_completed"
        eventSlot.captured.jobId shouldBe 42
        eventSlot.captured.telegramUserId shouldBe 100
        eventSlot.captured.telegramChatId shouldBe 200
        eventSlot.captured.platform shouldBe "youtube"
        eventSlot.captured.outputType shouldBe "audio"
        eventSlot.captured.cacheKey shouldBe "youtube:video:id:audio:preset"
        eventSlot.captured.sourceDurationSeconds shouldBe 120
        eventSlot.captured.downloadedFileSize shouldBe 1_000
        eventSlot.captured.telegramFileSize shouldBe 900
        eventSlot.captured.success shouldBe true
        eventSlot.captured.properties shouldBe mapOf("preset" to "youtube_audio")
        verify { transactionManager.getTransaction(match { it.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRES_NEW }) }
        verify { transactionManager.commit(any()) }
    }

    @Test
    fun `does not throw when analytics write fails`() {
        every { repository.save(any()) } throws IllegalStateException("database unavailable")

        service.record(
            RecordAnalyticsEventCommand(
                eventType = AnalyticsEventType.DOWNLOAD_FAILED,
                errorCode = "unknown_error",
            )
        )

        verify { transactionManager.rollback(any()) }
    }

    private fun mockTransactionManager(): PlatformTransactionManager {
        val transactionManager = mockk<PlatformTransactionManager>()
        every { transactionManager.getTransaction(any()) } returns SimpleTransactionStatus()
        every { transactionManager.commit(any()) } just Runs
        every { transactionManager.rollback(any()) } just Runs
        return transactionManager
    }
}
