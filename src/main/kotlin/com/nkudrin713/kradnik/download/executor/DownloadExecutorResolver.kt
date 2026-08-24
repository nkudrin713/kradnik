package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.request.DownloadRequest
import org.springframework.stereotype.Component

@Component
class DownloadExecutorResolver(
    executors: List<DownloadExecutor>,
) {
    private val executorsByStrategy = buildMap {
        executors.forEach { executor ->
            executor.strategies.forEach { strategy ->
                val existing = put(strategy, executor)
                require(existing == null) {
                    "Multiple download executors registered for strategy $strategy"
                }
            }
        }
    }

    fun resolve(request: DownloadRequest): DownloadExecutor {
        return executorsByStrategy[request.strategy]
            ?: throw IllegalStateException("No download executor registered for strategy ${request.strategy}")
    }
}
