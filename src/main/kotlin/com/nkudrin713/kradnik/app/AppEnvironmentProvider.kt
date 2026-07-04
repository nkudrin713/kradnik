package com.nkudrin713.kradnik.app

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class AppEnvironmentProvider(
    @Value("\${app.environment}")
    rawEnvironment: String,
) {
    val environment: AppEnvironment = AppEnvironment.fromConfig(rawEnvironment)
}
