package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.domain.OutputType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramDownloadChoiceViewTest {
    private val view = TelegramDownloadChoiceView()

    @Test
    fun createsOneRowPerOptionWithFormattedSizes() {
        val token = UUID.randomUUID()
        val keyboard = view.keyboard(
            sessionToken = token,
            options = listOf(
                option("video_original", "Оригинал", 1_420_000_000, approximate = true),
                option("video_720", "720p", 460_000_000, approximate = false),
                option("cover", "Скачать обложку", null, approximate = false),
            ),
        ).inlineKeyboard()

        assertEquals(listOf("Оригинал · ≈ 1.42 GB", "720p · 460 MB", "Скачать обложку"), keyboard.map { it.single().text })
        assertEquals(
            listOf("video_original", "video_720", "cover"),
            keyboard.map { DownloadChoiceCallback.parse(requireNotNull(it.single().callbackData))?.optionKey },
        )
    }

    @Test
    fun parsesValidCallbackAndRejectsInvalidOne() {
        val token = UUID.randomUUID()
        val encoded = DownloadChoiceCallback.encode(token, "audio")

        assertEquals(DownloadChoiceCallback(token, "audio"), DownloadChoiceCallback.parse(encoded))
        assertEquals(null, DownloadChoiceCallback.parse("dl:invalid:audio"))
        assertEquals(null, DownloadChoiceCallback.parse("mode:audio"))
    }

    @Test
    fun rejectsCallbackLongerThanTelegramLimit() {
        assertFailsWith<IllegalArgumentException> {
            DownloadChoiceCallback.encode(UUID.randomUUID(), "x".repeat(40))
        }
    }

    private fun option(
        key: String,
        label: String,
        size: Long?,
        approximate: Boolean,
    ): DownloadChoiceOptionSnapshot {
        return DownloadChoiceOptionSnapshot(
            key = key,
            label = label,
            sizeBytes = size,
            approximateSize = approximate,
            available = true,
            unavailableReason = null,
            originalUrl = URL,
            normalizedUrl = URL,
            cacheKey = "cache:$key",
            outputType = if (key == "cover") OutputType.COVER else OutputType.VIDEO,
            presetName = key,
            formatSelector = "best",
            extraArgs = emptyList(),
        )
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
