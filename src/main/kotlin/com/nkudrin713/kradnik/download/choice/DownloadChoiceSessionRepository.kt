package com.nkudrin713.kradnik.download.choice

import java.time.Instant
import java.util.UUID

/** Menu snapshots and atomic row access; the caller owns the transaction. */
interface DownloadChoiceSessionRepository {
    fun save(session: DownloadChoiceSession): DownloadChoiceSession
    fun findForUpdate(token: UUID): DownloadChoiceSession?
    fun deleteConsumed(cutoff: Instant): Int
    fun deleteExpiredUnselected(createdBefore: Instant): Int
}
