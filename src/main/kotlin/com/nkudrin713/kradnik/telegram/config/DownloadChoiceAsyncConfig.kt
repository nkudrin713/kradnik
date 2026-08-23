package com.nkudrin713.kradnik.telegram.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class DownloadChoiceAsyncConfig {
    @Bean(name = ["downloadChoiceExecutor"], destroyMethod = "shutdown")
    fun downloadChoiceExecutor(): ExecutorService {
        return Executors.newFixedThreadPool(2) { task ->
            Thread(task, "download-choice-worker").apply { isDaemon = true }
        }
    }
}
