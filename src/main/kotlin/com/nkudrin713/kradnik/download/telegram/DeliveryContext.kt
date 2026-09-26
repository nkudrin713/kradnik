package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadJob

/** Delivery inputs captured from the request, without queue state or source-specific download options. */
data class DeliveryContext(
    val chatId: Long,
    val replyToMessageId: Int? = null,
    val inlineMessageId: String? = null,
    val postText: String? = null,
) {
    companion object {
        fun fromJob(job: DownloadJob): DeliveryContext = DeliveryContext(
            chatId = job.telegramChatId,
            replyToMessageId = job.telegramRequestMessageId,
            inlineMessageId = job.telegramInlineMessageId,
            postText = job.sourcePostText,
        )
    }
}
