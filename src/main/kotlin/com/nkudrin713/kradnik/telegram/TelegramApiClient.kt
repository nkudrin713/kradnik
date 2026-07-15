package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

@Component
class TelegramApiClient(
    private val bot: TelegramBot,
) {
    fun <T, R> execute(
        request: BaseRequest<T, R>,
        errorContext: String? = null,
    ): R where T : BaseRequest<T, R>, R : BaseResponse {
        val response = bot.execute(request)
        if (!response.isOk) {
            val description = listOfNotNull(response.description(), errorContext)
                .joinToString(separator = " ")
            throw TelegramSendException(
                errorCode = response.errorCode(),
                description = description,
            )
        }

        return response
    }

    suspend fun <T, R> executeIo(
        request: BaseRequest<T, R>,
        errorContext: String? = null,
    ): R where T : BaseRequest<T, R>, R : BaseResponse {
        return withContext(Dispatchers.IO) {
            execute(request, errorContext)
        }
    }
}

class TelegramSendException(
    val errorCode: Int?,
    val description: String?,
    val kind: TelegramSendFailureKind = TelegramSendFailureKind.from(errorCode, description),
) : RuntimeException("Telegram send failed: code=$errorCode, description=$description") {
    constructor(description: String?) : this(null, description)

    fun isRetryable(): Boolean = kind == TelegramSendFailureKind.RETRYABLE

    fun isInvalidCachedFile(): Boolean = kind == TelegramSendFailureKind.INVALID_CACHED_FILE
}

enum class TelegramSendFailureKind {
    RETRYABLE,
    INVALID_CACHED_FILE,
    MESSAGE_NOT_MODIFIED,
    TERMINAL;

    companion object {
        fun from(errorCode: Int?, description: String?): TelegramSendFailureKind {
            val normalized = description?.lowercase().orEmpty()
            return when {
                errorCode == null -> RETRYABLE
                errorCode == 429 || errorCode >= 500 -> RETRYABLE
                errorCode == 400 && normalized.contains("message is not modified") -> MESSAGE_NOT_MODIFIED
                errorCode == 400 && isInvalidFileId(normalized) -> INVALID_CACHED_FILE
                else -> TERMINAL
            }
        }

        private fun isInvalidFileId(description: String): Boolean {
            return description.contains("wrong file identifier") ||
                    description.contains("file_id") && description.contains("invalid")
        }
    }
}
