package com.nkudrin713.kradnik.ytdlp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.process.ProcessCommand
import com.nkudrin713.kradnik.process.ProcessRunner
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.seconds

private const val YT_DLP = "yt-dlp"
private const val DUMP_SINGLE_JSON = "--dump-single-json"
private const val NO_PLAYLIST = "--no-playlist"
private const val NO_WARNINGS = "--no-warnings"
private const val FORMAT = "-f"
private const val AUDIO = "-x"

@Service
class YtDlpService(
    private val processRunner: ProcessRunner,
    private val objectMapper: ObjectMapper
) {

    suspend fun extractMetadata(url: String): YtDlpMetadataDto {
        val result = processRunner.run(
            ProcessCommand(
                YT_DLP,
                listOf(DUMP_SINGLE_JSON, NO_PLAYLIST, NO_WARNINGS, url),
                null,
                30.seconds
            )
        )

        if (result.timedOut) {
            throw YtDlpException("yt-dlp metadata extraction timed out")
        }
        if (result.exitCode != 0) {
            throw YtDlpException("yt-dlp metadata extraction failed: ${result.output.takeLast(500)}")
        }
        if (result.output.isBlank()) {
            throw YtDlpException("yt-dlp metadata extraction returned empty output")
        }

        return objectMapper.readValue<YtDlpMetadataDto>(result.output)
    }


}

class YtDlpException(message: String) : RuntimeException(message)
