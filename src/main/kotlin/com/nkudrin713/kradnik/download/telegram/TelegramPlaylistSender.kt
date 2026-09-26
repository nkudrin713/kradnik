package com.nkudrin713.kradnik.download.telegram

import com.nkudrin713.kradnik.download.domain.DownloadedFile
import com.nkudrin713.kradnik.download.domain.PlaylistAudioEntry
import com.nkudrin713.kradnik.download.domain.PlaylistAudioResult
import com.nkudrin713.kradnik.telegram.TelegramAudio
import com.nkudrin713.kradnik.telegram.TelegramMediaSender
import com.nkudrin713.kradnik.telegram.TelegramSendException
import com.nkudrin713.kradnik.telegram.config.TelegramBotProperties
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class TelegramPlaylistSender(private val telegramMediaSender: TelegramMediaSender, private val properties: TelegramBotProperties) {
    suspend fun stagePlaylistAudio(file: DownloadedFile, entry: PlaylistAudioEntry): String {
        val storageChatId = properties.fileStorageChatId ?: throw TelegramSendException(
            errorCode = null,
            description = "telegram.bot.file-storage-chat-id is not configured",
        )
        return telegramMediaSender.sendAudio(
            chatId = storageChatId,
            file = file.file,
            title = entry.title,
            performer = null,
            durationSeconds = entry.durationSeconds,
        )
    }

    suspend fun sendPlaylistAudios(
        context: DeliveryContext,
        playlistEntries: List<PlaylistAudioEntry>,
        results: List<PlaylistAudioResult>,
    ): List<String> {
        val entries = playlistEntries.associateBy(PlaylistAudioEntry::position)
        val audios = results.sortedBy(PlaylistAudioResult::position).mapNotNull { result ->
            val fileId = result.fileId ?: return@mapNotNull null
            val entry = entries[result.position] ?: return@mapNotNull null
            TelegramAudio(
                fileId = fileId,
                title = entry.title,
                durationSeconds = entry.durationSeconds,
            )
        }
        return telegramMediaSender.sendCachedAudios(
            chatId = context.chatId,
            audios = audios,
            replyToMessageId = context.replyToMessageId,
        )
    }

    suspend fun sendArchive(context: DeliveryContext, path: Path): String = telegramMediaSender.sendDocument(context.chatId, path, context.replyToMessageId)
}
