package com.nkudrin713.kradnik.download.limit

import com.nkudrin713.kradnik.ytdlp.dto.YtDlpMetadataDto
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AudioUploadPlannerTest {
    private val planner = AudioUploadPlanner()

    @Test
    fun selectsHighQualityForShortAudio() {
        val actual = planner.plan(metadata(durationSeconds = 30 * 60))

        assertEquals("128K", assertIs<AudioUploadPlan.Allowed>(actual).audioQuality)
    }

    @Test
    fun selectsFortyKForTwoHoursTwentyThreeMinutes() {
        val actual = planner.plan(metadata(durationSeconds = 8604))

        assertEquals("40K", assertIs<AudioUploadPlan.Allowed>(actual).audioQuality)
    }

    @Test
    fun selectsThirtyTwoKForThreeHours() {
        val actual = planner.plan(metadata(durationSeconds = 3 * 60 * 60))

        assertEquals("32K", assertIs<AudioUploadPlan.Allowed>(actual).audioQuality)
    }

    @Test
    fun rejectsWhenMinimumQualityCannotFitTargetSize() {
        val actual = planner.plan(metadata(durationSeconds = 4 * 60 * 60))

        assertIs<AudioUploadPlan.Rejected>(actual)
    }

    @Test
    fun returnsUnavailableWhenDurationIsMissing() {
        val actual = planner.plan(metadata(durationSeconds = null))

        assertIs<AudioUploadPlan.Unavailable>(actual)
    }

    private fun metadata(durationSeconds: Long?): YtDlpMetadataDto {
        return YtDlpMetadataDto(
            id = "id",
            title = "title",
            extractor = "youtube",
            webpageUrl = "https://example.com",
            thumbnail = null,
            duration = durationSeconds?.let { BigDecimal.valueOf(it) },
            ext = "mp4",
            width = 1920,
            height = 1080,
            fps = null,
            filesize = null,
            vcodec = null,
            acodec = null,
            filesizeApprox = null,
            formatId = "format",
            format = null,
            track = null,
            artist = null,
            creator = null,
            uploader = null,
            channel = null,
            requestedFormats = null,
        )
    }
}
