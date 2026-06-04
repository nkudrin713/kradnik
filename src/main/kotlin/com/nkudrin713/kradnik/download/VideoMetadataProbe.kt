package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.process.Command
import com.nkudrin713.kradnik.process.ProcessRunner
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Service
class VideoMetadataProbe(
    private val processRunner: ProcessRunner,
) {
    suspend fun probe(file: Path): VideoMetadata {
        val result = processRunner.run(
            FfprobeCommand(
                args = listOf(
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height,sample_aspect_ratio,display_aspect_ratio",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString(),
                ),
                timeout = 1.minutes,
            )
        )

        if (result.timedOut || result.exitCode != 0) {
            throw VideoMetadataProbeException("ffprobe failed: ${result.output.take(500)}")
        }

        val lines = result.output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val width = lines.getOrNull(0)?.toIntOrNull()
        val height = lines.getOrNull(1)?.toIntOrNull()
        val sampleAspectRatio = lines.getOrNull(2)
        val displayAspectRatio = lines.getOrNull(3)

        if (width == null || height == null || sampleAspectRatio == null || displayAspectRatio == null) {
            throw VideoMetadataProbeException("ffprobe returned invalid dimensions: ${result.output.take(100)}")
        }

        return VideoMetadata(
            width = width,
            height = height,
            sampleAspectRatio = sampleAspectRatio,
            displayAspectRatio = displayAspectRatio,
        )
    }
}

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val sampleAspectRatio: String,
    val displayAspectRatio: String,
) {
    val isVertical: Boolean = height > width
}

private data class FfprobeCommand(
    override val args: List<String>,
    override val timeout: Duration,
    override val workingDir: Path? = null,
    override val executable: String = "ffprobe",
) : Command

class VideoMetadataProbeException(message: String) : RuntimeException(message)
