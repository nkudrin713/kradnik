package com.nkudrin713.kradnik.settings

import org.springframework.data.jpa.repository.JpaRepository

interface ChatDownloadSettingRepository : JpaRepository<ChatDownloadSetting, Long>
