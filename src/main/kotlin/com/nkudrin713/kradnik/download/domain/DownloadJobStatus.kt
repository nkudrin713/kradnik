package com.nkudrin713.kradnik.download.domain

enum class DownloadJobStatus(val dbValue: String) {
	QUEUED("queued"),
	PROCESSING("processing"),
	UPLOADING("uploading"),
	COMPLETED("completed"),
	FAILED("failed");

	companion object {
		fun fromDb(value: String): DownloadJobStatus =
			entries.firstOrNull { it.dbValue == value }
				?: throw IllegalArgumentException("Unknown job status: $value")
	}
}
