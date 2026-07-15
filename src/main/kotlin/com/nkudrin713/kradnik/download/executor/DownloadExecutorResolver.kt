package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.request.DownloadRequest
import org.springframework.stereotype.Component

@Component
class DownloadExecutorResolver(
    private val executors: List<DownloadExecutor>,
) {
    fun resolve(request: DownloadRequest): DownloadExecutor {
        return executors.firstOrNull { it.supports(request) }
            ?: throw IllegalStateException("No download executor supports request")
    }
}
