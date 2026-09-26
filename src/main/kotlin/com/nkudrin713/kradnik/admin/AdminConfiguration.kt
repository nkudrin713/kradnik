package com.nkudrin713.kradnik.admin

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["admin.enabled"], havingValue = "true")
class AdminConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    fun adminDatabase(environment: Environment): AdminDatabase {
        val config = HikariConfig().apply {
            poolName = "admin-statistics"
            jdbcUrl = environment.getRequiredProperty("spring.datasource.url")
            username = environment.getRequiredProperty("spring.datasource.username")
            password = environment.getRequiredProperty("spring.datasource.password")
            maximumPoolSize = 1
            minimumIdle = 0
            connectionTimeout = 500
            validationTimeout = 250
            initializationFailTimeout = -1
            addDataSourceProperty("options", "-c statement_timeout=500 -c lock_timeout=100")
            addDataSourceProperty("connectTimeout", "2")
            addDataSourceProperty("socketTimeout", "2")
        }
        return AdminDatabase(HikariDataSource(config))
    }

    @Bean
    fun adminQueries(database: AdminDatabase): AdminQueries = AdminQueries(database.jdbc)

    @Bean
    fun adminStatisticsStore(database: AdminDatabase): AdminStatisticsStore = AdminStatisticsStore(database.jdbc)

    @Bean
    fun adminMemory(store: AdminStatisticsStore, clock: Clock): AdminMemory = AdminMemory(AdminMemoryProbe(), store, clock)

    @Bean
    fun adminSnapshots(queries: AdminQueries, runtime: AdminRuntime, clock: Clock, statistics: AdminStatistics, store: AdminStatisticsStore, memory: AdminMemory): AdminSnapshots = AdminSnapshots(queries, runtime, clock, statistics, store, memory)
}

/** One bounded connection shared by all dashboard reads and aggregate writes. */
class AdminDatabase(private val source: HikariDataSource) : AutoCloseable {
    val jdbc = JdbcTemplate(source).apply { queryTimeout = 1 }
    override fun close() = source.close()
}
