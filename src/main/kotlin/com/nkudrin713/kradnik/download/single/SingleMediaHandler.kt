package com.nkudrin713.kradnik.download.single

import com.nkudrin713.kradnik.download.domain.MediaArtifact
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.domain.SingleMediaRequest
import com.nkudrin713.kradnik.download.processing.JobProgress
import org.springframework.stereotype.Component
import java.nio.file.Path

interface SingleMediaHandler {
    suspend fun produce(request: SingleMediaRequest, directory: Path, jobId: Long, progress: JobProgress): MediaArtifact
}

@Component
class SingleMediaHandlers(video: VideoHandler, audio: AudioHandler, cover: CoverHandler, images: ImagesHandler) {
    private val handlers = mapOf(OutputType.VIDEO to video, OutputType.AUDIO to audio, OutputType.COVER to cover, OutputType.IMAGES to images)
    fun forOutput(type: OutputType): SingleMediaHandler = handlers.getValue(type)
}
