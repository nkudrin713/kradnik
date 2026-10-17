package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.delivery.DeliveryContext
import com.nkudrin713.kradnik.download.delivery.MediaDelivery
import com.nkudrin713.kradnik.download.delivery.ResultCache
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.domain.DownloadRequestMapper
import com.nkudrin713.kradnik.download.progress.DownloadPhase
import com.nkudrin713.kradnik.download.progress.JobNotifications
import com.nkudrin713.kradnik.download.single.SingleMediaHandlers
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Coordinates one job; handlers own source preparation and media production. No transaction spans external I/O. */
@Component
class DownloadJobProcessor(
    private val handlers: SingleMediaHandlers,
    private val cache: ResultCache,
    private val sender: MediaDelivery,
    private val lifecycle: JobLifecycle,
    private val progress: JobNotifications,
    private val workDirCleaner: WorkDirCleaner,
    private val jobProgressFactory: JobProgressFactory = JobProgressFactory(progress),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: DownloadJob) {
        var outputDir: Path? = null
        try {
            val request = DownloadRequestMapper.single(job)
            val context = DeliveryContext.fromJob(job)
            val cached = cache.find(request.cacheKey)
            if (cached != null) {
                val sentId = sender.sendCached(context, request.outputType, cached)
                if (sentId != null) {
                    lifecycle.complete(job, sentId)
                    return
                }
            }
            outputDir = workDirCleaner.create(job.requiredId())
            val jobProgress = jobProgressFactory.forJob(job)
            val artifact = handlers.forOutput(request.outputType).produce(request, outputDir, job.requiredId(), jobProgress)
            jobProgress.update(DownloadPhase.UPLOADING)
            val receipt = sender.send(context, artifact)
            lifecycle.complete(job, receipt)
        } catch (error: CancellationException) {
            if (lifecycle.isUserCancelled(job)) return
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: DownloadRejectedException) {
            lifecycle.fail(job, error)
        } catch (error: Exception) {
            logger.error("JOB[{}] failed", job.id, error)
            lifecycle.fail(job, error)
        } finally {
            outputDir?.let(workDirCleaner::deleteRecursively)
        }
    }
}
