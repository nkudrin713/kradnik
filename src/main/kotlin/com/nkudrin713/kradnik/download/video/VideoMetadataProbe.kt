package com.nkudrin713.kradnik.download.video

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
    private val objectMapper = jacksonObjectMapper()

    suspend fun probe(file: Path): VideoMetadata {
        val result = processRunner.run(
            FfprobeCommand(
                args = listOf(
                    "-v", "error",
                    "-show_entries",
                    "format=format_name:" +
                            "stream=codec_type,codec_name,codec_tag_string,profile,level,width,height,pix_fmt," +
                            "r_frame_rate,avg_frame_rate,sample_aspect_ratio,display_aspect_ratio," +
                            "color_space,color_transfer,color_primaries",
                    "-of", "json",
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

        val root = try {
            objectMapper.readTree(result.stdout)
        } catch (error: Exception) {
            throw VideoMetadataProbeException("ffprobe returned invalid JSON: ${result.stdout.take(100)}")
        }
        val streams = root.path(STREAMS)
        val videoStream = streams.firstOrNull { it.text(CODEC_TYPE) == VIDEO }
        val audioStream = streams.firstOrNull { it.text(CODEC_TYPE) == AUDIO }
        val width = videoStream?.int(WIDTH)
        val height = videoStream?.int(HEIGHT)
        val sampleAspectRatio = videoStream?.text(SAMPLE_ASPECT_RATIO)
        val displayAspectRatio = videoStream?.text(DISPLAY_ASPECT_RATIO)

        if (width == null || height == null || sampleAspectRatio == null || displayAspectRatio == null) {
            throw VideoMetadataProbeException("ffprobe returned invalid dimensions: ${result.stdout.take(100)}")
        }

        return VideoMetadata(
            width = width,
            height = height,
            sampleAspectRatio = sampleAspectRatio,
            displayAspectRatio = displayAspectRatio,
            containerFormat = root.path(FORMAT).text(FORMAT_NAME),
            videoCodec = videoStream?.text(CODEC_NAME),
            audioCodec = audioStream?.text(CODEC_NAME),
            codecTag = videoStream?.text(CODEC_TAG),
            codecProfile = videoStream?.text(PROFILE),
            codecLevel = videoStream?.int(LEVEL),
            pixelFormat = videoStream?.text(PIXEL_FORMAT),
            frameRate = videoStream?.text(AVERAGE_FRAME_RATE)
                ?.takeUnless { it == "0/0" }
                ?: videoStream?.text(REAL_FRAME_RATE)?.takeUnless { it == "0/0" },
            colorSpace = videoStream?.text(COLOR_SPACE),
            colorTransfer = videoStream?.text(COLOR_TRANSFER),
            colorPrimaries = videoStream?.text(COLOR_PRIMARIES),
        )
    }

    private fun JsonNode.text(fieldName: String): String? {
        return path(fieldName)
            .takeIf(JsonNode::isTextual)
            ?.asText()
            ?.takeIf(String::isNotBlank)
    }

    private fun JsonNode.int(fieldName: String): Int? {
        return path(fieldName)
            .takeIf(JsonNode::isIntegralNumber)
            ?.intValue()
    }

    private companion object {
        private const val STREAMS = "streams"
        private const val FORMAT = "format"
        private const val FORMAT_NAME = "format_name"
        private const val CODEC_TYPE = "codec_type"
        private const val CODEC_NAME = "codec_name"
        private const val CODEC_TAG = "codec_tag_string"
        private const val PROFILE = "profile"
        private const val LEVEL = "level"
        private const val WIDTH = "width"
        private const val HEIGHT = "height"
        private const val PIXEL_FORMAT = "pix_fmt"
        private const val REAL_FRAME_RATE = "r_frame_rate"
        private const val AVERAGE_FRAME_RATE = "avg_frame_rate"
        private const val SAMPLE_ASPECT_RATIO = "sample_aspect_ratio"
        private const val DISPLAY_ASPECT_RATIO = "display_aspect_ratio"
        private const val COLOR_SPACE = "color_space"
        private const val COLOR_TRANSFER = "color_transfer"
        private const val COLOR_PRIMARIES = "color_primaries"
        private const val VIDEO = "video"
        private const val AUDIO = "audio"
    }
}

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val sampleAspectRatio: String,
    val displayAspectRatio: String,
    val containerFormat: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
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
    val isMp4Container: Boolean = containerFormat
        ?.split(',')
        ?.any { it.equals("mp4", ignoreCase = true) }
        ?: false
}

private data class FfprobeCommand(
    override val args: List<String>,
    override val timeout: Duration,
    override val workingDir: Path? = null,
    override val executable: String = "ffprobe",
) : Command

class VideoMetadataProbeException(message: String) : RuntimeException(message)
