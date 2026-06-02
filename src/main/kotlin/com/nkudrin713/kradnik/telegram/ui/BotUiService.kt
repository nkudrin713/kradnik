package com.nkudrin713.kradnik.telegram.ui

import com.nkudrin713.kradnik.download.domain.DownloadOutputType
import com.nkudrin713.kradnik.download.domain.DownloadTask
import com.nkudrin713.kradnik.settings.DownloadMode
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

@Service
class BotUiService {
	fun queuedTaskMessage(task: DownloadTask): BotMessage =
		BotMessage(
			chatId = task.telegramChatId,
			text = taskStatusText(BotTaskStatus.QUEUED, task),
		)

	fun taskStatusMessage(status: BotTaskStatus, task: DownloadTask): BotMessage =
		BotMessage(
			chatId = task.telegramChatId,
			text = taskStatusText(status, task),
		)

	fun downloadModeMessage(chatId: Long, mode: DownloadMode): BotMessage =
		BotMessage(
			chatId = chatId,
			text = "Режим: ${mode.label()}",
			components = listOf(downloadModeButtons(mode)),
		)

	fun toSendMessage(message: BotMessage): SendMessage =
		SendMessage.builder()
			.chatId(message.chatId)
			.text(message.text)
			.replyMarkup(message.components.toInlineKeyboardMarkup())
			.build()

	fun toEditMessageText(message: BotMessage, messageId: Int): EditMessageText =
		EditMessageText.builder()
			.chatId(message.chatId)
			.messageId(messageId)
			.text(message.text)
			.replyMarkup(message.components.toInlineKeyboardMarkup())
			.build()

	fun toDeleteMessage(chatId: Long, messageId: Int): DeleteMessage =
		DeleteMessage.builder()
			.chatId(chatId)
			.messageId(messageId)
			.build()

	private fun taskStatusText(status: BotTaskStatus, task: DownloadTask): String {
		val type = when (task.outputType) {
			DownloadOutputType.AUDIO -> "аудио"
			DownloadOutputType.VIDEO -> "видео"
		}
		return "${status.label()}: $type"
	}

	private fun downloadModeButtons(mode: DownloadMode): BotButtonGroup =
		BotButtonGroup(
			buttons = listOf(
				BotButton(text = "${if (mode == DownloadMode.AUDIO) "• " else ""}Аудио", callbackData = "mode:audio"),
				BotButton(text = "${if (mode == DownloadMode.VIDEO) "• " else ""}Видео", callbackData = "mode:video"),
			)
		)

	private fun List<BotUiComponent>.toInlineKeyboardMarkup(): InlineKeyboardMarkup? {
		val rows = filterIsInstance<BotButtonGroup>()
			.map { group ->
				InlineKeyboardRow(
					group.buttons.map { button ->
						InlineKeyboardButton.builder()
							.text(button.text)
							.callbackData(button.callbackData)
							.build()
					}
				)
			}

		if (rows.isEmpty()) {
			return null
		}

		return InlineKeyboardMarkup.builder()
			.keyboard(rows)
			.build()
	}

	private fun BotTaskStatus.label(): String =
		when (this) {
			BotTaskStatus.QUEUED -> "В очереди"
			BotTaskStatus.DOWNLOADING -> "Скачиваю"
			BotTaskStatus.UPLOADING -> "Загружаю"
			BotTaskStatus.COMPLETED -> "Готово"
			BotTaskStatus.FAILED -> "Не получилось"
		}

	private fun DownloadMode.label(): String =
		when (this) {
			DownloadMode.AUDIO -> "аудио"
			DownloadMode.VIDEO -> "видео"
		}
}

data class BotMessage(
	val chatId: Long,
	val text: String,
	val components: List<BotUiComponent> = emptyList(),
)

sealed interface BotUiComponent

data class BotButtonGroup(
	val buttons: List<BotButton>,
) : BotUiComponent

data class BotButton(
	val text: String,
	val callbackData: String,
)

enum class BotTaskStatus {
	QUEUED,
	DOWNLOADING,
	UPLOADING,
	COMPLETED,
	FAILED,
}
