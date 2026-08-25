package com.nkudrin713.kradnik.download.choice

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface DownloadChoiceSessionRepository : JpaRepository<DownloadChoiceSession, UUID> {
    @Modifying
    @Query("DELETE FROM DownloadChoiceSession session WHERE session.expiresAt <= :cutoff")
    fun deleteExpired(cutoff: Instant): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT session FROM DownloadChoiceSession session WHERE session.token = :token")
    fun findForUpdate(token: UUID): DownloadChoiceSession?
}
