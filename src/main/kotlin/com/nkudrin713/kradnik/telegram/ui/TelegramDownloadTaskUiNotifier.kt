package com.nkudrin713.kradnik.telegram.ui

import com.nkudrin713.kradnik.download.pipeline.DownloadTaskUiNotifier
import com.nkudrin713.kradnik.download.service.DownloadTaskService
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.generics.TelegramClient

@Service
class TelegramDownloadTaskUiNotifier(
	private val downloadTaskService: DownloadTaskService,
	private val botUiService: BotUiService,
	private val telegramClient: TelegramClient,
) : DownloadTaskUiNotifier {
	override fun queued(taskId: Long) {
		val task = downloadTaskService.getTask(taskId)
		val message = telegramClient.execute(botUiService.toSendMessage(botUiService.queuedTaskMessage(task)))
		downloadTaskService.setStatusMessageId(taskId, message.messageId)
	}

	override fun downloading(taskId: Long) {
		update(taskId, BotTaskStatus.DOWNLOADING)
	}

	override fun uploading(taskId: Long) {
		update(taskId, BotTaskStatus.UPLOADING)
	}

	override fun completed(taskId: Long) {
		delete(taskId)
	}

	override fun failed(taskId: Long) {
		update(taskId, BotTaskStatus.FAILED)
	}

	private fun update(taskId: Long, status: BotTaskStatus) {
		val task = downloadTaskService.getTask(taskId)
		val messageId = task.telegramStatusMessageId ?: return
		telegramClient.execute(botUiService.toEditMessageText(botUiService.taskStatusMessage(status, task), messageId))
	}

	private fun delete(taskId: Long) {
		val task = downloadTaskService.getTask(taskId)
		val messageId = task.telegramStatusMessageId ?: return
		telegramClient.execute(botUiService.toDeleteMessage(task.telegramChatId, messageId))
	}
}
