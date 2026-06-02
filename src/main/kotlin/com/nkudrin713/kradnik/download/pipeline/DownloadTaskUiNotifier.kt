package com.nkudrin713.kradnik.download.pipeline

interface DownloadTaskUiNotifier {
	fun queued(taskId: Long) {
	}

	fun downloading(taskId: Long) {
	}

	fun uploading(taskId: Long) {
	}

	fun completed(taskId: Long) {
	}

	fun failed(taskId: Long) {
	}
}
