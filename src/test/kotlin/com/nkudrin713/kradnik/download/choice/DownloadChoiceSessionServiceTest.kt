package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.telegram.REPEAT_LAST_OPTION_KEY
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadChoiceSessionServiceTest {
    private val now = Instant.parse("2026-09-10T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository: DownloadChoiceSessionRepository = mockk()
    private val service = DownloadChoiceSessionService(
        repository = repository,
        messages = telegramMessages(),
        clock = clock,
        consumedSessionTtl = Duration.ofMinutes(30),
        unselectedSessionMaxAge = Duration.ofDays(30),
    )

    @Test
    fun createsSessionWithPlanSnapshot() {
        val saved = slot<DownloadChoiceSession>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val actual = service.create(createCommand())

        assertEquals(300, actual.telegramUserId)
        assertEquals(BotLanguage.EN, actual.language)
        assertEquals(listOf("video_720"), actual.options.map { it.key })
        assertEquals(now.plus(Duration.ofMinutes(30)), actual.cleanupAfter)
    }

    @Test
    fun storesFailedRequestWithoutReusingItsSelectedOption() {
        every { repository.save(any()) } answers { firstArg() }
        val result = service.createRetry(CreateDownloadRetryCommand(300, 100, 400, 200, 500, null, BotLanguage.RU, "https://youtu.be/id"))
        assertEquals("https://youtu.be/id", result.retryUrl)
        assertTrue(result.options.isEmpty())
        assertEquals(300, result.telegramUserId)
        assertEquals(500, result.telegramMenuMessageId)
        assertEquals(BotLanguage.RU, result.language)
    }

    @Test
    fun consumesRetryOnceAndValidatesOwnerMessageAndAge() {
        val retry = session().apply {
            retryUrl = "https://youtu.be/id"
            options = emptyList()
        }
        every { repository.findForUpdate(retry.token) } returns retry
        val command = selectCommand(retry.token).copy(optionKey = REPEAT_LAST_OPTION_KEY)
        assertEquals(DownloadChoiceSelection.NotOwner, service.select(command.copy(telegramUserId = 301)))
        assertEquals(DownloadChoiceSelection.Invalid, service.select(command.copy(telegramMenuMessageId = 501)))
        retry.createdAt = now.minus(Duration.ofDays(31))
        assertEquals(DownloadChoiceSelection.Invalid, service.select(command))
        retry.createdAt = now
        val result = assertIs<DownloadChoiceSelection.Retry>(service.select(command))
        assertEquals("https://youtu.be/id", result.url)
        assertEquals(now, retry.selectedAt)
        assertEquals(DownloadChoiceSelection.AlreadySelected, service.select(command))
    }

    @Test
    fun inlineRetryRequiresOriginalInlineMessage() {
        val retry = session().apply {
            retryUrl = "https://youtu.be/id"
            telegramInlineMessageId = "original"
        }
        every { repository.findForUpdate(retry.token) } returns retry
        val command = selectCommand(retry.token).copy(optionKey = REPEAT_LAST_OPTION_KEY, telegramInlineMessageId = "other")
        assertEquals(DownloadChoiceSelection.Invalid, service.select(command))
        assertIs<DownloadChoiceSelection.Retry>(service.select(command.copy(telegramInlineMessageId = "original")))
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
    fun cancelsUnselectedMenu() {
        val session = session()
        every { repository.findForUpdate(session.token) } returns session

        val actual = service.cancel(
            CancelDownloadChoiceCommand(
                token = session.token,
                telegramUserId = 300,
                telegramChatId = 100,
                telegramMenuMessageId = 500,
            ),
        )

        assertIs<DownloadChoiceCancellation.Cancelled>(actual)
        assertEquals(now, session.selectedAt)
        assertEquals(now.plus(Duration.ofMinutes(30)), session.cleanupAfter)
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
            ),
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

        val selected = session().apply { selectedAt = now }
        every { repository.findForUpdate(selected.token) } returns selected
        assertEquals(DownloadChoiceSelection.AlreadySelected, service.select(selectCommand(selected.token)))
    }

    @Test
    fun selectsAvailableOptionAfterRetentionDeadline() {
        val session = session().apply { cleanupAfter = now.minusSeconds(1) }
        every { repository.findForUpdate(session.token) } returns session

        val actual = service.select(selectCommand(session.token))

        assertIs<DownloadChoiceSelection.Ready>(actual)
        assertNotNull(session.selectedAt)
        assertTrue(session.cleanupAfter > now)
    }

    @Test
    fun rejectsUnselectedSessionAtAbsoluteMaxAge() {
        val session = session().apply { createdAt = now.minus(Duration.ofDays(30)) }
        every { repository.findForUpdate(session.token) } returns session

        val actual = service.select(selectCommand(session.token))

        assertEquals(DownloadChoiceSelection.Invalid, actual)
        assertNull(session.selectedAt)
    }

    @Test
    fun releasesClaimAfterStarterFailure() {
        val session = session().apply { selectedAt = now }
        every { repository.findForUpdate(session.token) } returns session

        service.release(session.token)

        assertNull(session.selectedAt)
    }

    @Test
    fun deletesExpiredConsumedAndUnselectedSessions() {
        every { repository.deleteConsumed(now) } returns 2
        every { repository.deleteExpiredUnselected(now.minus(Duration.ofDays(30))) } returns 3

        service.deleteExpiredSessions()

        verify(exactly = 1) { repository.deleteConsumed(now) }
        verify(exactly = 1) { repository.deleteExpiredUnselected(now.minus(Duration.ofDays(30))) }
    }

    @Test
    fun rejectsNonPositiveUnselectedSessionMaxAge() {
        assertFailsWith<IllegalArgumentException> {
            DownloadChoiceSessionService(
                repository = repository,
                messages = telegramMessages(),
                clock = clock,
                consumedSessionTtl = Duration.ofMinutes(30),
                unselectedSessionMaxAge = Duration.ZERO,
            )
        }
    }

    private fun createCommand(): CreateDownloadChoiceSessionCommand {
        return CreateDownloadChoiceSessionCommand(
            telegramUserId = 300,
            telegramChatId = 100,
            telegramUpdateId = 400,
            telegramRequestMessageId = 200,
            telegramMenuMessageId = 500,
            plan = DownloadChoicePlan(
                mediaInfo = DownloadChoiceMediaInfo(title = "Title", durationSeconds = 120),
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
            cleanupAfter = now.plusSeconds(60),
            createdAt = now.minus(Duration.ofDays(1)),
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
