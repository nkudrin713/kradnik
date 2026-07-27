package com.nkudrin713.kradnik.download.video

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
                    "-show_entries",
                    "stream=codec_name,codec_tag_string,profile,level,width,height,pix_fmt," +
                            "r_frame_rate,avg_frame_rate,sample_aspect_ratio,display_aspect_ratio," +
                            "color_space,color_transfer,color_primaries",
                    "-of", "default=noprint_wrappers=1",
                    file.toString(),
                ),
                timeout = 1.minutes,
            )
        )

        if (result.timedOut || result.exitCode != 0) {
            throw VideoMetadataProbeException("ffprobe failed: ${result.diagnosticOutput.takeLast(500)}")
        }
        if (result.stdoutTruncated) {
            throw VideoMetadataProbeException("ffprobe output exceeded capture limit")
        }

        val values = result.stdout
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it.contains('=') }
            .associate { line ->
                line.substringBefore('=') to line.substringAfter('=')
            }
        val width = values["width"]?.toIntOrNull()
        val height = values["height"]?.toIntOrNull()
        val sampleAspectRatio = values["sample_aspect_ratio"]
        val displayAspectRatio = values["display_aspect_ratio"]

        if (width == null || height == null || sampleAspectRatio == null || displayAspectRatio == null) {
            throw VideoMetadataProbeException("ffprobe returned invalid dimensions: ${result.stdout.take(100)}")
        }

        return VideoMetadata(
            width = width,
            height = height,
            sampleAspectRatio = sampleAspectRatio,
            displayAspectRatio = displayAspectRatio,
            codecName = values["codec_name"],
            codecTag = values["codec_tag_string"],
            codecProfile = values["profile"],
            codecLevel = values["level"]?.toIntOrNull(),
            pixelFormat = values["pix_fmt"],
            frameRate = values["avg_frame_rate"]
                ?.takeUnless { it == "0/0" }
                ?: values["r_frame_rate"]?.takeUnless { it == "0/0" },
            colorSpace = values["color_space"],
            colorTransfer = values["color_transfer"],
            colorPrimaries = values["color_primaries"],
        )
    }
}

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val sampleAspectRatio: String,
    val displayAspectRatio: String,
    val codecName: String? = null,
    val codecTag: String? = null,
    val codecProfile: String? = null,
    val codecLevel: Int? = null,
    val pixelFormat: String? = null,
    val frameRate: String? = null,
    val colorSpace: String? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
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
