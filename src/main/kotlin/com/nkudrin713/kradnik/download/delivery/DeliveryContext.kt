package com.nkudrin713.kradnik.download.delivery

import com.nkudrin713.kradnik.download.domain.DownloadJob

/** Destination and legacy caption data. New posts carry text in their artifact or cached result. */
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
