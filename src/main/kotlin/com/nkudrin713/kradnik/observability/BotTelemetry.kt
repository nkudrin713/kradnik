package com.nkudrin713.kradnik.observability

import com.nkudrin713.kradnik.download.processing.DownloadPhase

/** Stable execution-boundary contract. Implementations must only update bounded memory, never perform I/O. */
interface BotTelemetry {
    fun register(id: String, category: String) {}
    fun busy(id: String, jobId: Long? = null, platform: String? = null) {}
    fun state(id: String, state: String) {}
    fun phase(jobId: Long, phase: DownloadPhase) {}
    fun playlist(jobId: Long, waiting: Int) {}
    fun item(jobId: Long, started: Boolean) {}
    fun metadataStarted(): String? = null
    fun metadataQueue(delta: Int) {}
    fun error(kind: RuntimeError) {}

    companion object {
        val NONE: BotTelemetry = object : BotTelemetry {}
    }
}

enum class RuntimeError { METADATA, PLAYLIST_ITEM, WORKER, METADATA_REJECTED }
