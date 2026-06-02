package com.nkudrin713.kradnik

import com.fasterxml.jackson.databind.ObjectMapper
import com.nkudrin713.kradnik.download.repository.DownloadTaskRepository
import com.nkudrin713.kradnik.process.ProcessRunner
import com.nkudrin713.kradnik.settings.DownloadSettingsRepository
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.telegram.telegrambots.meta.generics.TelegramClient

@SpringBootTest(
	properties = [
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
		"download.worker.enabled=false",
		"telegram.bot.token=",
	]
)
class KradnikApplicationTests {
	@Test
	fun contextLoads() {
	}

	@TestConfiguration
	class Mocks {
		@Bean
		fun downloadTaskRepository(): DownloadTaskRepository = mockk(relaxed = true)

		@Bean
		fun downloadSettingsRepository(): DownloadSettingsRepository = mockk(relaxed = true)

		@Bean
		fun processRunner(): ProcessRunner = mockk(relaxed = true)

		@Bean
		fun objectMapper(): ObjectMapper = mockk(relaxed = true)

		@Bean
		fun telegramClient(): TelegramClient = mockk(relaxed = true)
	}

}
