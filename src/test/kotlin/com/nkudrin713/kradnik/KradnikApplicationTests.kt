package com.nkudrin713.kradnik

import com.nkudrin713.kradnik.download.DownloadTaskRepository
import com.nkudrin713.kradnik.settings.DownloadSettingRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
	properties = [
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
	]
)
class KradnikApplicationTests {
	@MockitoBean
	lateinit var downloadTaskRepository: DownloadTaskRepository

	@MockitoBean
	lateinit var downloadSettingRepository: DownloadSettingRepository

	@Test
	fun contextLoads() {
	}

}
