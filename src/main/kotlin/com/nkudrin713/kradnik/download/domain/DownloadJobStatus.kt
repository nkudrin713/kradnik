package com.nkudrin713.kradnik.download.domain

enum class DownloadJobStatus(val dbValue: String) {
	QUEUED("queued"),
	PROCESSING("processing"),
	COMPLETED("completed"),
	FAILED("failed"),
	CANCELLED_BY_USER("cancelled_by_user");

	companion object {
		fun fromDb(value: String): DownloadJobStatus =
			entries.firstOrNull { it.dbValue == value }
				?: throw IllegalArgumentException("Unknown job status: $value")
	}
}
