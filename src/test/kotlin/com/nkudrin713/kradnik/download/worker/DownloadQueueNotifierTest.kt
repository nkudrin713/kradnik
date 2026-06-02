package com.nkudrin713.kradnik.download.worker

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate

class DownloadQueueNotifierTest {
    @Test
    fun `sends notify immediately without transaction`() {
        val jdbcTemplate: JdbcTemplate = mockk()
        every { jdbcTemplate.execute("NOTIFY download_tasks") } returns Unit
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton("jdbcTemplate", jdbcTemplate)

        val notifier = DownloadQueueNotifier(beanFactory.getBeanProvider(JdbcTemplate::class.java))

        notifier.notifyAfterCommit()

        verify { jdbcTemplate.execute("NOTIFY download_tasks") }
    }
}
