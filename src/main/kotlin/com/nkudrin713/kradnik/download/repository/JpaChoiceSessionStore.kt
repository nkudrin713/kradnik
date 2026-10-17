package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.choice.DownloadChoiceSession
import com.nkudrin713.kradnik.download.choice.DownloadChoiceSessionRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JpaChoiceSessionStore(private val repository: JpaDownloadChoiceSessionRepository) : DownloadChoiceSessionRepository {
    override fun save(session: DownloadChoiceSession): DownloadChoiceSession = repository.save(DownloadChoiceSessionEntity.from(session)).toSession()
    override fun findForUpdate(token: UUID) = repository.findForUpdate(token)?.toSession()
    override fun deleteConsumed(cutoff: Instant) = repository.deleteConsumed(cutoff)
    override fun deleteExpiredUnselected(createdBefore: Instant) = repository.deleteExpiredUnselected(createdBefore)
}
