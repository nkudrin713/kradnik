package com.nkudrin713.kradnik.download.executor

import com.nkudrin713.kradnik.download.domain.DownloadSpec
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

    fun resolve(spec: DownloadSpec): DownloadExecutor {
        return executorsByStrategy[spec.strategy]
            ?: throw IllegalStateException("No download executor registered for strategy ${spec.strategy}")
    }
}
