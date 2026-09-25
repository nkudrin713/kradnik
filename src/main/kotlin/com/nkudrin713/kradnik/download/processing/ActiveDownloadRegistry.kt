package com.nkudrin713.kradnik.download.processing

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Tracks only currently executing jobs so a user cancellation can reach their coroutine. */
@Component
class ActiveDownloadRegistry {
    private val active = ConcurrentHashMap<Long, Job>()

    suspend fun run(jobId: Long, block: suspend () -> Unit) = coroutineScope {
        val execution = launch(start = CoroutineStart.LAZY) { block() }
        check(active.putIfAbsent(jobId, execution) == null) { "Job $jobId is already active" }
        try {
            execution.start()
            execution.join()
        } finally {
            active.remove(jobId, execution)
        }
    }

    fun cancel(jobId: Long): Boolean = active[jobId]?.let {
        it.cancel(UserDownloadCancellationException(jobId))
        true
    } ?: false
}

class UserDownloadCancellationException(jobId: Long) : java.util.concurrent.CancellationException("Download job $jobId was cancelled by the user")
