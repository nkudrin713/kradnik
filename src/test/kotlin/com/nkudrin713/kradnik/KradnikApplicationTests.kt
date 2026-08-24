package com.nkudrin713.kradnik

import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionRepository
import com.nkudrin713.kradnik.download.repository.DownloadJobRepository
import com.nkudrin713.kradnik.process.ProcessRunner
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

@SpringBootTest(
	properties = [
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"app.environment=test",
		"telegram.bot.token=test-token",
		"download.worker.enabled=false",
	]
)
class KradnikApplicationTests {
	@Test
	fun contextLoads() {
	}

	@TestConfiguration
	class Mocks {
		@Bean
		fun downloadJobRepository(): DownloadJobRepository = mockk(relaxed = true)

		@Bean
		fun downloadChoiceSessionRepository(): DownloadChoiceSessionRepository = mockk(relaxed = true)

		@Bean
		fun processRunner(): ProcessRunner = mockk(relaxed = true)

		@Bean
		fun jdbcTemplate(): JdbcTemplate = mockk(relaxed = true)

		@Bean
		fun platformTransactionManager(): PlatformTransactionManager = mockk(relaxed = true)
	}

}
