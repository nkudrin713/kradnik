package com.nkudrin713.kradnik.download.worker

import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

private const val NOTIFY_SQL = "NOTIFY download_tasks"

@Service
class DownloadQueueNotifier(
    private val jdbcTemplateProvider: ObjectProvider<JdbcTemplate>,
) {
    fun notifyAfterCommit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            notifyNow()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    notifyNow()
                }
            }
        )
    }

    private fun notifyNow() {
        jdbcTemplateProvider.ifAvailable { jdbcTemplate ->
            jdbcTemplate.execute(NOTIFY_SQL)
        }
    }
}
