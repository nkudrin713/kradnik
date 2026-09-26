package com.nkudrin713.kradnik.download.processing

import com.nkudrin713.kradnik.download.cleanup.WorkDirCleaner
import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.domain.DownloadRejectedException
import com.nkudrin713.kradnik.download.repository.DownloadRequestMapper
import com.nkudrin713.kradnik.download.single.SingleMediaHandlers
import com.nkudrin713.kradnik.download.telegram.DeliveryContext
import com.nkudrin713.kradnik.download.telegram.TelegramFileSender
import com.nkudrin713.kradnik.download.telegram.TelegramJobProgress
import com.nkudrin713.kradnik.download.telegram.TelegramResultCache
import com.nkudrin713.kradnik.telegram.TelegramSendException
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Coordinates one job; handlers own source preparation and media production. No transaction spans external I/O. */
@Component
class DownloadJobProcessor(
    private val handlers: SingleMediaHandlers,
    private val cache: TelegramResultCache,
    private val sender: TelegramFileSender,
    private val lifecycle: JobLifecycle,
    private val progress: TelegramJobProgress,
    private val workDirCleaner: WorkDirCleaner,
    private val jobProgressFactory: JobProgressFactory = JobProgressFactory(progress),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun process(job: DownloadJob) {
        var outputDir: Path? = null
        try {
            val request = DownloadRequestMapper.single(job)
            val context = DeliveryContext.fromJob(job)
            val cached = cache.find(request)
            if (cached != null) {
                val sentId = try {
                    sender.sendCached(context, cached)
                } catch (error: TelegramSendException) {
                    if (!error.isInvalidCachedFile()) throw error
                    null
                }
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
