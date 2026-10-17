package com.nkudrin713.kradnik.download.delivery

import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.OutputType

/** Receipts are opaque to scenarios. Only an unusable cached reference returns null; other failures propagate. */
interface MediaDelivery {
    suspend fun send(context: DeliveryContext, artifact: MediaArtifact): String
    suspend fun sendCached(context: DeliveryContext, type: OutputType, receipt: String): String?
}
