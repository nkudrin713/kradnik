package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.TestConfiguration
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
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(DownloadJobRepositoryTestApplication::class)
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
class DownloadJobRepositoryIntegrationTest @Autowired constructor(
    private val repository: DownloadJobRepository,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @BeforeEach
    fun cleanDatabase() {
        repository.deleteAll()
    }

    @Test
    fun appliesFlywayMigrationsIncludingProcessingLease() {
        val columnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'download_jobs'
                  AND column_name IN ('telegram_update_id', 'lease_token', 'lease_expires_at')
            """.trimIndent(),
            Int::class.java,
        )

        assertEquals(3, columnCount)
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
            repository.requeueStaleInProgressJobs(Instant.now(), 3)
            repository.failStaleInProgressJobs(Instant.now(), 3)
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
                    leaseExpiresAt = Instant.now().plusSeconds(3600),
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
@EntityScan(basePackageClasses = [DownloadJob::class])
@EnableJpaRepositories(basePackageClasses = [DownloadJobRepository::class])
class DownloadJobRepositoryTestApplication
