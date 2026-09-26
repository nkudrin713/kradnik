package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.admin.AdminQueries
import com.nkudrin713.kradnik.admin.AdminStatistics
import com.nkudrin713.kradnik.admin.AdminStatisticsStore
import com.nkudrin713.kradnik.admin.ErrorMinute
import com.nkudrin713.kradnik.admin.HistoryPoint
import com.nkudrin713.kradnik.admin.MemoryPoint
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSession
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionRepository
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadJobStatus
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.download.domain.PlaylistDeliveryMode
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.service.CreateDownloadJobCommand
import com.nkudrin713.kradnik.download.service.DownloadJobService
import com.nkudrin713.kradnik.observability.RuntimeError
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
import kotlin.test.assertTrue

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
        jdbcTemplate.execute("TRUNCATE admin_error_minutes, admin_queue_minutes, admin_memory_minutes")
    }

    @Test
    fun memoryHistoryRestoresNullsAndIgnoresOlderRetriesAndPrunesExpiredRows() {
        val store = AdminStatisticsStore(jdbcTemplate)
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(10)
        val point = MemoryPoint(now, 100, 200, 300, 40, 20, 10, null, null, null, null)
        store.saveMemoryHistory(listOf(point, point.copy(at = now.minusSeconds(8 * 86400))))
        val updated = point.copy(at = now.plusSeconds(1), heapUsed = 150, containerUsed = 500, containerLimit = 1000, gcCount = 2, gcTimeMillis = 4, gcWindowSeconds = 60)
        store.saveMemoryHistory(listOf(updated))
        store.saveMemoryHistory(listOf(point))
        assertEquals(listOf(updated), AdminStatisticsStore(jdbcTemplate).memoryHistory())
        store.saveMemoryHistory(listOf(point.copy(at = now.plusSeconds(2))))
        assertEquals(listOf(point.copy(at = now.plusSeconds(2))), store.memoryHistory())
        store.prune()
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_memory_minutes", Long::class.java))
    }

    @Test
    fun adminStatisticsSurviveRestartAndPersistenceRetriesAreIdempotent() {
        val store = AdminStatisticsStore(jdbcTemplate)
        val first = AdminStatistics(true, store = store)
        first.restore()
        first.error(RuntimeError.METADATA)
        first.error(RuntimeError.PLAYLIST_ITEM)
        first.stop()
        val second = AdminStatistics(true, store = store)
        second.restore()
        second.error(RuntimeError.METADATA)
        second.flush()
        second.flush()
        assertEquals(2L, second.snapshot().first().counts["METADATA"])
        val third = AdminStatistics(true, store = store)
        third.restore()
        assertEquals(2L, third.snapshot().first().counts["METADATA"])
        assertEquals(1L, third.snapshot().first().counts["PLAYLIST_ITEM"])
        val row = ErrorMinute(Instant.now().epochSecond / 60, RuntimeError.WORKER, 3)
        store.saveErrors("retry-fixture", listOf(row))
        store.saveErrors("retry-fixture", listOf(row))
        store.saveErrors("retry-fixture", listOf(row.copy(count = 1)))
        assertEquals(3L, store.errorsExcept("unused").single { it.kind == RuntimeError.WORKER }.count)
        val point = HistoryPoint(Instant.now(), 7, 3)
        store.saveHistory(listOf(point, point))
        store.saveHistory(listOf(point.copy(at = point.at.minusMillis(1), queued = 100)))
        assertEquals(7L, store.history().single().queued)
        store.saveHistory(listOf(point.copy(at = point.at.plusMillis(1), queued = 8)))
        assertEquals(8L, store.history().single().queued)
        store.saveErrors("expired", listOf(row.copy(minute = row.minute - 8 * 1440)))
        store.saveHistory(listOf(point.copy(at = point.at.minusSeconds(8 * 86400))))
        store.prune()
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_error_minutes WHERE instance_id = 'expired'", Long::class.java))
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_queue_minutes", Long::class.java))
    }

    @Test
    fun adminAggregatesUseCompletionTimeAndKeepCancellationsAndWorkloadsSeparate() {
        fun insert(status: String, minutes: Int, workload: String = "single") {
            jdbcTemplate.update(
                """
                INSERT INTO download_jobs (telegram_user_id, telegram_chat_id, original_url, normalized_url,
                    cache_key, download_preset, selected_format, platform, status, workload_type, created_at, completed_at)
                VALUES (1, 1, 'https://example.com', 'https://example.com', 'admin-test', '', '', 'youtube', ?, ?,
                    now() - interval '3 days', CASE WHEN ? IN ('queued', 'processing') THEN NULL
                    ELSE now() - ? * interval '1 minute' END)
                """.trimIndent(),
                status,
                workload,
                status,
                minutes,
            )
        }
        insert("queued", 0)
        insert("processing", 0, "playlist_audio")
        insert("failed", 5)
        insert("failed", 30, "playlist_audio")
        insert("completed", 120)
        insert("cancelled_by_user", 5)
        insert("failed", 1500)
        val queries = AdminQueries(jdbcTemplate)
        val queues = queries.queues()
        assertEquals(1L, queues.single { it.category == "single" }.count)
        assertEquals("processing", queues.single { it.category == "playlist_audio" }.status)
        assertTrue(queues.all { it.oldest.isBefore(Instant.now().minusSeconds(2 * 86400)) })
        val outcomes = queries.outcomes()
        assertEquals(1L, outcomes.single { it.minutes == 15 && it.status == "failed" }.count)
        assertEquals(2L, outcomes.filter { it.minutes == 60 && it.status == "failed" }.sumOf { it.count })
        assertEquals(1L, outcomes.single { it.minutes == 1440 && it.status == "completed" }.count)
        assertEquals(1L, outcomes.single { it.minutes == 15 && it.status == "cancelled_by_user" }.count)
        assertEquals(2L, outcomes.filter { it.minutes == 1440 && it.status == "failed" }.sumOf { it.count })
    }

    @Test
    fun adminPlansUsePartialIndexesWithHistoricalPayloads() {
        jdbcTemplate.execute(
            """
            INSERT INTO download_jobs (telegram_user_id, telegram_chat_id, original_url, normalized_url,
                cache_key, download_preset, selected_format, platform, status, completed_at)
            SELECT 1, 1, 'https://example.com', 'https://example.com', 'history-' || n, '', '', 'youtube',
                CASE WHEN n <= 10 THEN 'queued' ELSE 'completed' END,
                CASE WHEN n <= 10 THEN NULL WHEN n <= 100 THEN now() ELSE now() - interval '7 days' END
            FROM generate_series(1, 30000) n
            """.trimIndent(),
        )
        jdbcTemplate.execute("ANALYZE download_jobs")
        listOf(AdminQueries.QUEUES_SQL, AdminQueries.OUTCOMES_SQL).forEach { sql ->
            val plan = jdbcTemplate.queryForList("EXPLAIN (ANALYZE, BUFFERS) $sql", String::class.java).joinToString("\n")
            println("Admin aggregate plan:\n$plan")
            assertTrue(plan.contains("idx_download_jobs_admin_"), plan)
            assertTrue(!plan.contains("Seq Scan on download_jobs"), plan)
        }
    }

    @Test
    fun migrationRemovesLeaseRetryAndTransientMetadataColumns() {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM information_schema.columns WHERE table_name = 'download_jobs'
            AND column_name IN ('lease_token', 'lease_expires_at', 'attempts', 'next_attempt_at',
                'source_audio_title', 'source_audio_performer', 'source_duration_seconds')
        """,
            Int::class.java,
        )
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
            ),
        )
        val coverJob = repository.saveAndFlush(
            job("cover").apply {
                outputType = OutputType.COVER
                platform = DownloadPlatform.YOUTUBE
                language = BotLanguage.RU
            },
        )
        val imageJob = repository.saveAndFlush(
            job("images").apply {
                outputType = OutputType.IMAGES
                platform = DownloadPlatform.INSTAGRAM
                language = BotLanguage.RU
                sourcePostText = "Post text"
            },
        )
        val postJob = repository.saveAndFlush(job("post").apply { outputType = OutputType.POST })
        assertEquals(OutputType.POST, repository.findById(requireNotNull(postJob.id)).orElseThrow().outputType)
        val preference = preferenceRepository.saveAndFlush(
            TelegramUserPreference(
                telegramUserId = 1,
                language = BotLanguage.RU,
            ),
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
            ),
        )
        val expiredUnselectedSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 11,
                telegramMenuMessageId = 21,
                cleanupAfter = now.plusSeconds(3600),
            ),
        )
        val consumedSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 12,
                telegramMenuMessageId = 22,
                cleanupAfter = expiredDeadline,
                selectedAt = expiredDeadline,
            ),
        )
        val activeConsumedSession = choiceSessionRepository.saveAndFlush(
            DownloadChoiceSession(
                telegramUpdateId = 13,
                telegramMenuMessageId = 23,
                cleanupAfter = now.plusSeconds(3600),
                selectedAt = now,
            ),
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
                ),
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it != null })
        assertEquals(1, results.count { it == null })
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
                ),
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
    fun workerTypesClaimOnlyTheirOwnJobs() {
        val single = repository.saveAndFlush(job("single"))
        val playlist = repository.saveAndFlush(
            job("playlist").apply {
                workloadType = DownloadWorkloadType.PLAYLIST_AUDIO
                playlistDeliveryMode = PlaylistDeliveryMode.ZIP
                playlistTitle = "Playlist archive"
                playlistEntries = listOf(
                    PlaylistAudioEntry(1, "video-1", "https://youtu.be/video-1", "Episode", 60),
                )
            },
        )

        assertEquals(single.id, downloadJobService.claimNextQueuedJob()?.id)
        val claimedPlaylist = downloadJobService.claimNextQueuedPlaylistJob()
        assertEquals(playlist.id, claimedPlaylist?.id)
        assertEquals(DownloadWorkloadType.PLAYLIST_AUDIO, claimedPlaylist?.workloadType)
        assertEquals(PlaylistDeliveryMode.ZIP, claimedPlaylist?.playlistDeliveryMode)
        assertEquals("Playlist archive", claimedPlaylist?.playlistTitle)
        assertEquals(PlaylistDeliveryMode.AUDIO_MESSAGES, single.playlistDeliveryMode)
    }

    @Test
    fun cancellationIsTerminalAndKeepsJob() {
        val queued = repository.saveAndFlush(job("cancel"))

        assertEquals(true, downloadJobService.cancelByUser(queued.requiredId(), queued.telegramUserId))

        val persisted = repository.findById(queued.requiredId()).orElseThrow()
        assertEquals(DownloadJobStatus.CANCELLED_BY_USER, persisted.status)
        assertNotNull(persisted.completedAt)
        assertNull(downloadJobService.claimNextQueuedJob())
    }

    @Test
    fun persistsPlaylistResultWhileJobIsProcessing() {
        val playlist = repository.saveAndFlush(
            job("playlist-result").apply { workloadType = DownloadWorkloadType.PLAYLIST_AUDIO },
        )
        assertEquals(playlist.id, downloadJobService.claimNextQueuedPlaylistJob()?.id)

        assertEquals(
            true,
            downloadJobService.savePlaylistResult(
                playlist.requiredId(),
                PlaylistAudioResult(position = 1, fileId = "audio-file"),
            ),
        )

        assertEquals(
            listOf(PlaylistAudioResult(position = 1, fileId = "audio-file")),
            repository.findById(playlist.requiredId()).orElseThrow().playlistResults,
        )
    }

    @Test
    fun returnsLatestCompletedCachedJob() {
        val older = repository.saveAndFlush(
            job("shared-cache").apply {
                status = DownloadJobStatus.COMPLETED
                telegramFileId = "older-file"
                completedAt = Instant.parse("2026-01-01T00:00:00Z")
            },
        )
        val newer = repository.saveAndFlush(
            job("shared-cache").apply {
                status = DownloadJobStatus.COMPLETED
                telegramFileId = "newer-file"
                completedAt = Instant.parse("2026-01-02T00:00:00Z")
            },
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
                pool.submit(
                    Callable {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        downloadJobService.claimNextQueuedJob()
                    },
                )
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
    ],
)
@Import(DownloadJobService::class)
class DownloadJobRepositoryTestApplication
