package com.nkudrin713.kradnik.analytics

import com.nkudrin713.kradnik.app.AppEnvironmentProvider
import com.nkudrin713.kradnik.download.domain.OutputType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Service
class AnalyticsEventService(
    private val analyticsEventRepository: AnalyticsEventRepository,
    private val appEnvironmentProvider: AppEnvironmentProvider,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun record(command: RecordAnalyticsEventCommand) {
        try {
            transactionTemplate.executeWithoutResult {
                analyticsEventRepository.save(command.toEntity(appEnvironmentProvider.environment.value))
            }
        } catch (error: Exception) {
            logger.warn("Analytics event recording failed: type={}", command.eventType.dbValue, error)
        }
    }

    private fun RecordAnalyticsEventCommand.toEntity(environment: String): AnalyticsEvent {
        return AnalyticsEvent(
            environment = environment,
            eventType = eventType.dbValue,
            jobId = jobId,
            telegramUserId = telegramUserId,
            telegramChatId = telegramChatId,
            platform = platform,
            outputType = outputType?.dbValue,
            cacheKey = cacheKey,
            sourceDurationSeconds = sourceDurationSeconds,
            downloadedFileSize = downloadedFileSize,
            telegramFileSize = telegramFileSize,
            success = success,
            errorCode = errorCode,
            properties = properties,
        )
    }
}

data class RecordAnalyticsEventCommand(
    val eventType: AnalyticsEventType,
    val jobId: Long? = null,
    val telegramUserId: Long? = null,
    val telegramChatId: Long? = null,
    val platform: String? = null,
    val outputType: OutputType? = null,
    val cacheKey: String? = null,
    val sourceDurationSeconds: Int? = null,
    val downloadedFileSize: Long? = null,
    val telegramFileSize: Long? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
    val properties: Map<String, Any?> = emptyMap(),
)
