package com.nkudrin713.kradnik.analytics

import org.springframework.data.jpa.repository.JpaRepository

interface AnalyticsEventRepository : JpaRepository<AnalyticsEvent, Long>
