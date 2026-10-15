package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.domain.DownloadWorkloadType
import com.nkudrin713.kradnik.download.service.DownloadJobService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class TelegramQueueUpdater(private val jobs: DownloadJobService, private val sender: TelegramSender) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val positions = mutableMapOf<Long, Long>()
    private var nextOffset = 0

    @Scheduled(fixedDelay = 5000)
    fun refresh() {
        val queued = try {
            jobs.queuedJobs()
        } catch (error: Exception) {
            logger.warn("Queue status refresh failed", error)
            return
        }
        positions.keys.retainAll(queued.map { it.requiredId() }.toSet())
        val counters = mutableMapOf<DownloadWorkloadType, Long>()
        val updates = mutableListOf<Pair<com.nkudrin713.kradnik.download.domain.DownloadJob, Long>>()
        for (job in queued) {
            val position = (counters[job.workloadType] ?: 0L) + 1
            counters[job.workloadType] = position
            val id = job.requiredId()
            if (positions[id] != position) updates += job to position
        }
        if (updates.isEmpty()) return
        val start = nextOffset % updates.size
        for (index in 0 until minOf(20, updates.size)) {
            val offset = (start + index) % updates.size
            val (job, position) = updates[offset]
            val id = job.requiredId()
            nextOffset = (offset + 1) % updates.size
            val address = job.telegramInlineMessageId?.let(TelegramMessageAddress::Inline)
                ?: job.telegramStatusMessageId?.let { TelegramMessageAddress.Chat(job.telegramChatId, it) }
                ?: continue
            try {
                sender.editQueuedJob(address, id, job.language, job.workloadType, position) { jobs.isQueued(id) }
                positions[id] = position
            } catch (error: Exception) {
                logger.warn("Queued job status refresh failed: jobId={}", id, error)
            }
        }
    }
}
