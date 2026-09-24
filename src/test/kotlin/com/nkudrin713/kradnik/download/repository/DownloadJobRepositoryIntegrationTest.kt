package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSession
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionRepository
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreference
import com.nkudrin713.kradnik.telegram.localization.TelegramUserPreferenceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(DownloadJobRepositoryTestApplication::class)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
class DownloadJobRepositoryIntegrationTest @Autowired constructor(
    private val repository: DownloadJobRepository,
    private val choiceSessionRepository: DownloadChoiceSessionRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val downloadJobService: DownloadJobService,
    private val preferenceRepository: TelegramUserPreferenceRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @BeforeEach
    fun cleanDatabase() {
        choiceSessionRepository.deleteAll()
        repository.deleteAll()
        preferenceRepository.deleteAll()
    }

    @Test
    fun migrationRemovesLeaseRetryAndTransientMetadataColumns() {
        val count = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM information_schema.columns WHERE table_name = 'download_jobs'
            AND column_name IN ('lease_token', 'lease_expires_at', 'attempts', 'next_attempt_at',
                'source_audio_title', 'source_audio_performer', 'source_duration_seconds')
        """, Int::class.java)
        assertEquals(0, count)
    }

    @Test
    fun persistsDownloadChoiceSessionAndDerivedOutputTypes() {
        val option = DownloadChoiceOptionSnapshot(
            key = "cover",
            label = "Скачать обложку",
            sizeBytes = null,
            approximateSize = false,
            available = true,
            unavailableReason = null,
            spec = DownloadSpec(
                originalUrl = "https://example.com/video",
                normalizedUrl = "https://example.com/video",
                cacheKey = "cover-cache",
                outputType = OutputType.COVER,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "youtube_cover",
                formatSelector = "best",
            ),
        )
        val session = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUserId = 1,
                telegramChatId = 2,
                telegramUpdateId = 3,
                telegramRequestMessageId = 4,
                telegramMenuMessageId = 5,
                language = BotLanguage.RU,
                options = listOf(option),
                cleanupAfter = Instant.now().plusSeconds(60),
            )
        )
        val coverJob = repository.saveAndFlush(
            job("cover").apply {
                outputType = OutputType.COVER
                platform = DownloadPlatform.YOUTUBE
                language = BotLanguage.RU
            }
        )
        val imageJob = repository.saveAndFlush(
            job("images").apply {
                outputType = OutputType.IMAGES
                platform = DownloadPlatform.INSTAGRAM
                language = BotLanguage.RU
                sourcePostText = "Post text"
            }
        )
        val preference = preferenceRepository.saveAndFlush(
            TelegramUserPreference(
                telegramUserId = 1,
                language = BotLanguage.RU,
            )
        )

        val persistedOption = choiceSessionRepository.findById(session.token).orElseThrow().options.single()
        val persistedJob = repository.findById(requireNotNull(coverJob.id)).orElseThrow()
        val persistedImageJob = repository.findById(requireNotNull(imageJob.id)).orElseThrow()
        assertEquals(OutputType.COVER, persistedOption.spec.outputType)
        assertEquals(DownloadPlatform.YOUTUBE, persistedOption.spec.platform)
        assertEquals(OutputType.COVER, persistedJob.outputType)
        assertEquals(DownloadPlatform.YOUTUBE, persistedJob.platform)
        assertEquals(OutputType.IMAGES, persistedImageJob.outputType)
        assertEquals(DownloadPlatform.INSTAGRAM, persistedImageJob.platform)
        assertEquals("Post text", persistedImageJob.sourcePostText)
        assertEquals(BotLanguage.RU, choiceSessionRepository.findById(session.token).orElseThrow().language)
        assertEquals(BotLanguage.RU, persistedJob.language)
        assertEquals(BotLanguage.RU, preferenceRepository.findById(preference.telegramUserId).orElseThrow().language)
    }

    @Test
    fun deletesExpiredChoiceSessionsBySelectionState() {
        val now = Instant.now()
        val expiredDeadline = now.minusSeconds(1)
        val availableSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 10,
                telegramMenuMessageId = 20,
                cleanupAfter = expiredDeadline,
            )
        )
        val expiredUnselectedSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 11,
                telegramMenuMessageId = 21,
                cleanupAfter = now.plusSeconds(3600),
            )
        )
        val consumedSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 12,
                telegramMenuMessageId = 22,
                cleanupAfter = expiredDeadline,
                selectedAt = expiredDeadline,
            )
        )
        val activeConsumedSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 13,
                telegramMenuMessageId = 23,
                cleanupAfter = now.plusSeconds(3600),
                selectedAt = now,
            )
        )
        jdbcTemplate.update(
            "UPDATE download_choice_sessions SET created_at = ? WHERE token = ?",
            Timestamp.from(now.minus(Duration.ofDays(31))),
            expiredUnselectedSession.token,
        )

        val deleted = transactionTemplate.execute {
            val consumed = choiceSessionRepository.deleteConsumed(now)
            val unselected = choiceSessionRepository.deleteExpiredUnselected(now.minus(Duration.ofDays(30)))
            consumed to unselected
        }

        assertEquals(1 to 1, deleted)
        assertEquals(true, choiceSessionRepository.existsById(availableSession.token))
        assertEquals(false, choiceSessionRepository.existsById(expiredUnselectedSession.token))
        assertEquals(false, choiceSessionRepository.existsById(consumedSession.token))
        assertEquals(true, choiceSessionRepository.existsById(activeConsumedSession.token))
    }

    @Test
    fun createsOnlyOneJobForConcurrentTelegramUpdate() {
        val command = CreateDownloadJobCommand(
            telegramUserId = 1,
            telegramChatId = 2,
            telegramUpdateId = 123,
            spec = DownloadSpec(
                originalUrl = "https://example.com/raw",
                normalizedUrl = "https://example.com/normalized",
                cacheKey = "same-update",
                outputType = OutputType.VIDEO,
                platform = DownloadPlatform.YOUTUBE,
                presetName = "preset",
                formatSelector = "format",
            ),
        )
        val executor = Executors.newFixedThreadPool(2)

        val results = try {
            executor.invokeAll(
                listOf(
                    Callable { downloadJobService.createJob(command) },
                    Callable { downloadJobService.createJob(command) },
                )
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        assertEquals(1, repository.count())
    }

    @Test
    fun claimsDifferentJobsConcurrently() {
        repository.saveAllAndFlush(listOf(job("first"), job("second")))
        val executor = Executors.newFixedThreadPool(2)

        val claimed = try {
            executor.invokeAll(
                listOf(
                    Callable { claimNextJob() },
                    Callable { claimNextJob() },
                )
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertNotNull(claimed[0])
        assertNotNull(claimed[1])
        assertNotEquals(claimed[0].id, claimed[1].id)
        assertEquals(setOf(DownloadJobStatus.PROCESSING), claimed.map { it.status }.toSet())
    }

    @Test
    fun returnsLatestCompletedCachedJob() {
        val older = repository.saveAndFlush(
            job("shared-cache").apply {
                status = DownloadJobStatus.COMPLETED
                telegramFileId = "older-file"
                completedAt = Instant.parse("2026-01-01T00:00:00Z")
            }
        )
        val newer = repository.saveAndFlush(
            job("shared-cache").apply {
                status = DownloadJobStatus.COMPLETED
                telegramFileId = "newer-file"
                completedAt = Instant.parse("2026-01-02T00:00:00Z")
            }
        )

        val cached = repository.findCachedCompletedJob("shared-cache")

        assertEquals(newer.id, cached?.id)
        assertNotEquals(older.id, cached?.id)
    }

    @Test
    fun oneJobCanOnlyBeClaimedOnceUnderContention() {
        repository.saveAndFlush(job("one"))
        val barrier = java.util.concurrent.CyclicBarrier(8)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                pool.submit(Callable {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    downloadJobService.claimNextQueuedJob()
                })
            }
            assertEquals(1, futures.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }.count { it != null })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun completesAndFailsInShortTransactionsAndRecoversOnlyProcessing() {
        repository.saveAllAndFlush(listOf(job("complete"), job("fail"), job("interrupted")))
        val completed = claimNextJob()
        val failed = claimNextJob()
        val interrupted = claimNextJob()
        assertNotNull(completed.startedAt)
        downloadJobService.markCompleted(completed, "file")
        downloadJobService.markFailed(failed, "source failed")
        assertEquals(1, downloadJobService.recoverInterruptedJobs())
        assertEquals(DownloadJobStatus.COMPLETED, repository.findById(completed.requiredId()).orElseThrow().status)
        assertEquals(DownloadJobStatus.FAILED, repository.findById(failed.requiredId()).orElseThrow().status)
        assertEquals(interrupted.id, claimNextJob().id)
        assertNull(downloadJobService.claimNextQueuedJob())
    }

    private fun claimNextJob(): DownloadJob = requireNotNull(downloadJobService.claimNextQueuedJob())

    private fun job(cacheKey: String): DownloadJob {
        return DownloadJob(
            telegramUserId = 1,
            telegramChatId = 2,
            originalUrl = "https://example.com/$cacheKey",
            normalizedUrl = "https://example.com/$cacheKey",
            cacheKey = cacheKey,
            downloadPreset = "preset",
            selectedFormat = "format",
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@TestConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = [DownloadJob::class, DownloadChoiceSession::class, TelegramUserPreference::class])
@EnableJpaRepositories(
    basePackageClasses = [
        DownloadJobRepository::class,
        DownloadChoiceSessionRepository::class,
        TelegramUserPreferenceRepository::class,
    ]
)
@Import(DownloadJobService::class)
class DownloadJobRepositoryTestApplication
