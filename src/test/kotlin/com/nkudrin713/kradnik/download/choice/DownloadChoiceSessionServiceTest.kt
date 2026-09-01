package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadChoiceSessionServiceTest {
    private val repository: DownloadChoiceSessionRepository = mockk()
    private val service = DownloadChoiceSessionService(repository, Duration.ofMinutes(30))

    @Test
    fun createsSessionWithPlanSnapshot() {
        val saved = slot<DownloadChoiceSession>()
        every { repository.deleteConsumed(any()) } returns 2
        every { repository.save(capture(saved)) } answers { saved.captured }

        val actual = service.create(createCommand())

        assertEquals(300, actual.telegramUserId)
        assertEquals(listOf("video_720"), actual.options.map { it.key })
        assertNotNull(actual.cleanupAfter)
        verify(exactly = 1) { repository.deleteConsumed(any()) }
    }

    @Test
    fun atomicallySelectsAvailableOption() {
        val session = session()
        every { repository.findForUpdate(session.token) } returns session

        val actual = service.select(selectCommand(session.token))

        assertIs<DownloadChoiceSelection.Ready>(actual)
        assertNotNull(session.selectedAt)
    }

    @Test
    fun selectsInlineSessionByInlineMessageId() {
        val session = session().apply {
            telegramMenuMessageId = null
            telegramInlineMessageId = "inline-message"
        }
        every { repository.findForUpdate(session.token) } returns session

        val actual = service.select(
            selectCommand(session.token).copy(
                telegramChatId = null,
                telegramMenuMessageId = null,
                telegramInlineMessageId = "inline-message",
            )
        )

        assertIs<DownloadChoiceSelection.Ready>(actual)
    }

    @Test
    fun rejectsForeignUnavailableAndRepeatedSelections() {
        val missingToken = UUID.randomUUID()
        every { repository.findForUpdate(missingToken) } returns null
        assertEquals(DownloadChoiceSelection.Invalid, service.select(selectCommand(missingToken)))

        val foreign = session()
        every { repository.findForUpdate(foreign.token) } returns foreign
        assertEquals(
            DownloadChoiceSelection.NotOwner,
            service.select(selectCommand(foreign.token).copy(telegramUserId = 301)),
        )

        val unavailable = session(option = option(available = false))
        every { repository.findForUpdate(unavailable.token) } returns unavailable
        assertIs<DownloadChoiceSelection.Unavailable>(service.select(selectCommand(unavailable.token)))

        val selected = session().apply { selectedAt = Instant.now() }
        every { repository.findForUpdate(selected.token) } returns selected
        assertEquals(DownloadChoiceSelection.AlreadySelected, service.select(selectCommand(selected.token)))
    }

    @Test
    fun selectsAvailableOptionAfterRetentionDeadline() {
        val session = session().apply { cleanupAfter = Instant.now().minusSeconds(1) }
        every { repository.findForUpdate(session.token) } returns session

        val actual = service.select(selectCommand(session.token))

        assertIs<DownloadChoiceSelection.Ready>(actual)
        assertNotNull(session.selectedAt)
        assertTrue(session.cleanupAfter > Instant.now())
    }

    @Test
    fun releasesClaimAfterStarterFailure() {
        val session = session().apply { selectedAt = Instant.now() }
        every { repository.findForUpdate(session.token) } returns session

        service.release(session.token)

        assertNull(session.selectedAt)
    }

    private fun createCommand(): CreateDownloadChoiceSessionCommand {
        return CreateDownloadChoiceSessionCommand(
            telegramUserId = 300,
            telegramChatId = 100,
            telegramUpdateId = 400,
            telegramRequestMessageId = 200,
            telegramMenuMessageId = 500,
            plan = DownloadChoicePlan(
                mediaInfo = DownloadChoiceMediaInfo("Channel", "Title", 120),
                options = listOf(option()),
            ),
        )
    }

    private fun selectCommand(token: UUID): SelectDownloadChoiceCommand {
        return SelectDownloadChoiceCommand(
            token = token,
            optionKey = "video_720",
            telegramUserId = 300,
            telegramChatId = 100,
            telegramMenuMessageId = 500,
        )
    }

    private fun session(option: DownloadChoiceOptionSnapshot = option()): DownloadChoiceSession {
        return DownloadChoiceSession(
            token = UUID.randomUUID(),
            telegramUserId = 300,
            telegramChatId = 100,
            telegramUpdateId = 400,
            telegramRequestMessageId = 200,
            telegramMenuMessageId = 500,
            options = listOf(option),
            cleanupAfter = Instant.now().plusSeconds(60),
        )
    }

    private fun option(available: Boolean = true): DownloadChoiceOptionSnapshot {
        return DownloadChoiceOptionSnapshot(
            key = "video_720",
            label = "720p",
            sizeBytes = 100_000_000,
            approximateSize = false,
            available = available,
            unavailableReason = if (available) null else "too large",
            spec = DownloadSpec(
                originalUrl = URL,
                normalizedUrl = URL,
                cacheKey = "cache",
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "youtube_video_720",
                formatSelector = "22",
            ),
        )
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
