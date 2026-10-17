package com.nkudrin713.kradnik.download.delivery

sealed interface MessageAddress {
    data class Chat(val chatId: Long, val messageId: Int) : MessageAddress

    data class Inline(val inlineMessageId: String) : MessageAddress
}
