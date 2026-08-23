package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSession
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionRepository
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.ratelimit.PostgresRateLimitBucketStore
import com.nkudrin713.kradnik.download.ratelimit.RateLimitBucketKey
import com.nkudrin713.kradnik.download.ratelimit.RateLimitBucketStore
import com.nkudrin713.kradnik.download.ratelimit.RateLimitCoordinator
import com.nkudrin713.kradnik.download.ratelimit.RateLimitDecision
import com.nkudrin713.kradnik.download.ratelimit.RateLimitPolicy
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.CreateDownloadJobResult
import com.nkudrin713.kradnik.download.service.DownloadJobService
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(DownloadJobRepositoryTestApplication::class)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
class DownloadJobRepositoryIntegrationTest @Autowired constructor(
    private val repository: DownloadJobRepository,
    private val choiceSessionRepository: DownloadChoiceSessionRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val rateLimitBucketStore: RateLimitBucketStore,
    private val downloadJobService: DownloadJobService,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_rate_limit_buckets")
        choiceSessionRepository.deleteAll()
        repository.deleteAll()
    }

    @Test
    fun appliesFlywayMigrationsIncludingProcessingLease() {
        val columnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'download_jobs'
                  AND column_name IN ('telegram_update_id', 'lease_token', 'lease_expires_at', 'next_attempt_at')
            """.trimIndent(),
            Int::class.java,
        )
        val rateLimitTableCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_name = 'request_rate_limit_buckets'
            """.trimIndent(),
            Int::class.java,
        )

        assertEquals(4, columnCount)
        assertEquals(1, rateLimitTableCount)
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
            originalUrl = "https://example.com/video",
            normalizedUrl = "https://example.com/video",
            cacheKey = "cover-cache",
            outputType = OutputType.COVER,
            presetName = "youtube_cover",
            formatSelector = "best",
            extraArgs = emptyList(),
        )
        val session = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUserId = 1,
                telegramChatId = 2,
                telegramUpdateId = 3,
                telegramRequestMessageId = 4,
                telegramMenuMessageId = 5,
                originalUrl = option.originalUrl,
                normalizedUrl = option.normalizedUrl,
                options = listOf(option),
                expiresAt = Instant.now().plusSeconds(60),
            )
        )
        val coverJob = repository.saveAndFlush(job("cover").apply { outputType = OutputType.COVER })

        assertEquals(OutputType.COVER, choiceSessionRepository.findById(session.token).orElseThrow().options.single().outputType)
        assertEquals(OutputType.COVER, repository.findById(requireNotNull(coverJob.id)).orElseThrow().outputType)
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
    fun grantsOnlyOneConcurrentRateLimitPermit() {
        val now = Instant.parse("2026-07-15T10:00:00Z")
        val coordinator = RateLimitCoordinator(
            store = rateLimitBucketStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val key = RateLimitBucketKey("instagram", "embed", "vps-direct")
        val policy = RateLimitPolicy(
            minInterval = Duration.ofSeconds(30),
            maxJitter = Duration.ZERO,
            initialCooldown = Duration.ofMinutes(30),
            maxCooldown = Duration.ofHours(6),
            cooldownMultiplier = 2,
        )
        val executor = Executors.newFixedThreadPool(2)

        val decisions = try {
            executor.invokeAll(
                listOf(
                    Callable { coordinator.acquire(key, policy) },
                    Callable { coordinator.acquire(key, policy) },
                )
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, decisions.count { it is RateLimitDecision.Granted })
        assertEquals(1, decisions.count { it is RateLimitDecision.Deferred })
    }

    @Test
    fun createsOnlyOneJobForConcurrentTelegramUpdate() {
        val command = CreateDownloadJobCommand(
            telegramUserId = 1,
            telegramChatId = 2,
            telegramUpdateId = 123,
            originalUrl = "https://example.com/raw",
            normalizedUrl = "https://example.com/normalized",
            cacheKey = "same-update",
            outputType = OutputType.VIDEO,
            downloadPreset = "preset",
            selectedFormat = "format",
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

        assertEquals(1, results.count { it is CreateDownloadJobResult.Created })
        assertEquals(1, results.count { it is CreateDownloadJobResult.Existing })
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

        assertEquals(0, staleUpdate)
        assertEquals(1, ownedUpdate)
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
@EntityScan(basePackageClasses = [DownloadJob::class, DownloadChoiceSession::class])
@EnableJpaRepositories(basePackageClasses = [DownloadJobRepository::class, DownloadChoiceSessionRepository::class])
@Import(PostgresRateLimitBucketStore::class, DownloadJobService::class)
class DownloadJobRepositoryTestApplication
