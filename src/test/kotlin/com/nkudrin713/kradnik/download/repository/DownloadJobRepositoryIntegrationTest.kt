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
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.UUID
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
    fun appliesFlywayMigrationsIncludingProcessingLease() {
        val columnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'download_jobs'
                  AND column_name IN (
                      'telegram_update_id',
                      'lease_token',
                      'lease_expires_at',
                      'next_attempt_at',
                      'platform',
                      'language'
                  )
            """.trimIndent(),
            Int::class.java,
        )
        val removedRateLimitTableCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_name = 'request_rate_limit_buckets'
            """.trimIndent(),
            Int::class.java,
        )
        val preferenceTableCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_name = 'telegram_user_preferences'
            """.trimIndent(),
            Int::class.java,
        )
        val removedJobColumnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'download_jobs'
                  AND column_name IN (
                      'source_title',
                      'source_extractor',
                      'telegram_file_size',
                      'processing_started_at',
                      'uploading_started_at',
                      'downloaded_at'
                  )
            """.trimIndent(),
            Int::class.java,
        )

        assertEquals(6, columnCount)
        assertEquals(1, preferenceTableCount)
        assertEquals(0, removedRateLimitTableCount)
        assertEquals(0, removedJobColumnCount)
    }

    @Test
    fun persistsDownloadChoiceSessionAndCoverOutputType() {
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
        val preference = preferenceRepository.saveAndFlush(
            TelegramUserPreference(
                telegramUserId = 1,
                language = BotLanguage.RU,
            )
        )

        val persistedOption = choiceSessionRepository.findById(session.token).orElseThrow().options.single()
        val persistedJob = repository.findById(requireNotNull(coverJob.id)).orElseThrow()
        assertEquals(OutputType.COVER, persistedOption.spec.outputType)
        assertEquals(DownloadPlatform.YOUTUBE, persistedOption.spec.platform)
        assertEquals(OutputType.COVER, persistedJob.outputType)
        assertEquals(DownloadPlatform.YOUTUBE, persistedJob.platform)
        assertEquals(BotLanguage.RU, choiceSessionRepository.findById(session.token).orElseThrow().language)
        assertEquals(BotLanguage.RU, persistedJob.language)
        assertEquals(BotLanguage.RU, preferenceRepository.findById(preference.telegramUserId).orElseThrow().language)
    }

    @Test
    fun deletesOnlyConsumedChoiceSessionsAfterRetentionDeadline() {
        val cleanupDeadline = Instant.now().minusSeconds(1)
        val availableSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 10,
                telegramMenuMessageId = 20,
                cleanupAfter = cleanupDeadline,
            )
        )
        val consumedSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 11,
                telegramMenuMessageId = 21,
                cleanupAfter = cleanupDeadline,
                selectedAt = cleanupDeadline,
            )
        )

        val deleted = transactionTemplate.execute {
            choiceSessionRepository.deleteConsumed(Instant.now())
        }

        assertEquals(1, deleted)
        assertEquals(true, choiceSessionRepository.existsById(availableSession.token))
        assertEquals(false, choiceSessionRepository.existsById(consumedSession.token))
    }

    @Test
    fun doesNotClaimJobBeforeNextAttemptTime() {
        val future = repository.saveAndFlush(
            job("future").apply {
                nextAttemptAt = Instant.now().plusSeconds(3600)
            }
        )
        val due = repository.saveAndFlush(
            job("due").apply {
                nextAttemptAt = Instant.now().minusSeconds(1)
            }
        )

        val claimed = claimNextJob()

        assertEquals(due.id, claimed.id)
        assertEquals(DownloadJobStatus.QUEUED, repository.findById(future.id!!).orElseThrow().status)
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
    fun rejectsStateTransitionFromStaleLeaseOwner() {
        val saved = repository.saveAndFlush(job("lease-fencing"))
        val staleToken = UUID.randomUUID()
        val currentToken = UUID.randomUUID()
        jdbcTemplate.update(
            """
                UPDATE download_jobs
                SET status = 'processing', lease_token = ?, lease_expires_at = now() + INTERVAL '5 minutes'
                WHERE id = ?
            """.trimIndent(),
            currentToken,
            saved.id,
        )

        val staleUpdate = transactionTemplate.execute {
            repository.markOwnedUploading(requireNotNull(saved.id), staleToken)
        }
        val ownedUpdate = transactionTemplate.execute {
            repository.markOwnedUploading(requireNotNull(saved.id), currentToken)
        }

        assertNull(staleUpdate)
        assertEquals(DownloadJobStatus.UPLOADING, ownedUpdate?.status)
        assertEquals(DownloadJobStatus.UPLOADING, repository.findById(saved.id!!).orElseThrow().status)
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
        assertEquals(setOf(1), claimed.map { it.attempts }.toSet())
    }

    @Test
    fun recoversStaleJobsAccordingToAttemptCount() {
        val retryable = repository.saveAndFlush(job("retryable"))
        val exhausted = repository.saveAndFlush(job("exhausted"))
        jdbcTemplate.update(
            """
                UPDATE download_jobs
                SET status = 'processing', attempts = 1,
                    lease_token = gen_random_uuid(), lease_expires_at = now() - INTERVAL '2 hours'
                WHERE id = ?
            """.trimIndent(),
            retryable.id,
        )
        jdbcTemplate.update(
            """
                UPDATE download_jobs
                SET status = 'uploading', attempts = 3,
                    lease_token = gen_random_uuid(), lease_expires_at = now() - INTERVAL '2 hours'
                WHERE id = ?
            """.trimIndent(),
            exhausted.id,
        )

        transactionTemplate.executeWithoutResult {
            repository.requeueStaleInProgressJobs(3)
            repository.failStaleInProgressJobs(3)
        }

        assertEquals(DownloadJobStatus.QUEUED, repository.findById(retryable.id!!).orElseThrow().status)
        assertEquals(DownloadJobStatus.FAILED, repository.findById(exhausted.id!!).orElseThrow().status)
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

    private fun claimNextJob(): DownloadJob {
        return requireNotNull(
            transactionTemplate.execute {
                repository.claimNextQueuedJob(
                    maxAttempts = 3,
                    leaseToken = UUID.randomUUID(),
                    leaseDurationMs = 3_600_000,
                )
            }
        )
    }

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
