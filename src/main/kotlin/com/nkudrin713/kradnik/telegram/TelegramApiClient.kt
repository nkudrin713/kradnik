package com.nkudrin713.kradnik.telegram

import com.pengrad.telegrambot.Callback
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.springframework.stereotype.Component

/**
 * Executes Telegram SDK requests for [TelegramSender] and [TelegramMediaSender].
 * Unsuccessful responses become [TelegramSendException]. Media requests are cancelled with their worker.
 */
@Component
class TelegramApiClient(
    private val bot: TelegramBot,
) {
    fun <T, R> execute(
        request: BaseRequest<T, R>,
        errorContext: String? = null,
    ): R where T : BaseRequest<T, R>, R : BaseResponse {
        return checked(bot.execute(request), errorContext)
    }

    private fun <R : BaseResponse> checked(response: R, errorContext: String?): R {
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
        request: T,
        errorContext: String? = null,
    ): R where T : BaseRequest<T, R>, R : BaseResponse {
        return suspendCancellableCoroutine { continuation ->
            val call = bot.execute(request, object : Callback<T, R> {
                override fun onResponse(request: T, response: R) {
                    val result = try {
                        checked(response, errorContext)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                        return
                    }
                    continuation.resume(result)
                }

                override fun onFailure(request: T, error: IOException) {
                    continuation.resumeWithException(error)
                }
            })
            continuation.invokeOnCancellation { call.cancel() }
        }
    }
}

/**
 * Carries a failed [TelegramApiClient] response classified for stale cached-file recovery, unchanged messages,
 * or terminal handling. [DownloadJobProcessor][com.nkudrin713.kradnik.download.processing.DownloadJobProcessor] uses
 * the classification without parsing Telegram error text again.
 */
class TelegramSendException(
    errorCode: Int?,
    description: String?,
    val kind: TelegramSendFailureKind = TelegramSendFailureKind.from(errorCode, description),
) : RuntimeException("Telegram send failed: code=$errorCode, description=$description") {
    constructor(description: String?) : this(null, description)

    fun isInvalidCachedFile(): Boolean = kind == TelegramSendFailureKind.INVALID_CACHED_FILE
}

enum class TelegramSendFailureKind {
    INVALID_CACHED_FILE,
    MESSAGE_NOT_MODIFIED,
    OTHER;

    companion object {
        fun from(errorCode: Int?, description: String?): TelegramSendFailureKind {
            val normalized = description?.lowercase().orEmpty()
            return when {
                errorCode == 400 && normalized.contains("message is not modified") -> MESSAGE_NOT_MODIFIED
                errorCode == 400 && isInvalidFileId(normalized) -> INVALID_CACHED_FILE
                else -> OTHER
            }
        }

        private fun isInvalidFileId(description: String): Boolean {
            return description.contains("wrong file identifier") ||
                    description.contains("file_id") && description.contains("invalid")
        }
    }
}
