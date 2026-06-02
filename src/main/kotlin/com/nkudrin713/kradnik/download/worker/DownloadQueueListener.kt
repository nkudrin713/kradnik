package com.nkudrin713.kradnik.download.worker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.postgresql.PGConnection
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import javax.sql.DataSource

private const val LISTEN_SQL = "LISTEN download_tasks"
private const val LISTEN_TIMEOUT_MILLIS = 30_000

@Service
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(
    name = ["download.worker.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DownloadQueueListener(
    private val dataSource: DataSource,
) {
    suspend fun listen(onNotification: suspend () -> Unit) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true

            connection.createStatement().use { statement ->
                statement.execute(LISTEN_SQL)
            }

            val pgConnection = connection.unwrap(PGConnection::class.java)

            while (currentCoroutineContext().isActive) {
                val notifications = pgConnection.getNotifications(LISTEN_TIMEOUT_MILLIS)
                if (!notifications.isNullOrEmpty()) {
                    onNotification()
                }
            }
        }
    }
}
