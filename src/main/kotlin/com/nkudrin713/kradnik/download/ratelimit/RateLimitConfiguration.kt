package com.nkudrin713.kradnik.download.ratelimit

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class RateLimitConfiguration {
    @Bean
    fun rateLimitClock(): Clock = Clock.systemUTC()
}
