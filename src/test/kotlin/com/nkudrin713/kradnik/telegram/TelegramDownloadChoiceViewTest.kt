package com.nkudrin713.kradnik.telegram

import com.nkudrin713.kradnik.download.choice.DownloadChoiceMediaInfo
import com.nkudrin713.kradnik.download.choice.DownloadChoiceOptionSnapshot
import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.telegramMessages
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramDownloadChoiceViewTest {
    private val view = TelegramDownloadChoiceView(telegramMessages())

    @Test
    fun createsPreformattedEscapedTitleAndNonClickableDuration() {
        val actual = view.text(
            DownloadChoiceMediaInfo(
                channelName = "Channel <official>",
                title = "Title & more",
                durationSeconds = 3_723,
            ),
            BotLanguage.RU,
        )

        assertEquals(
            """
                <pre>Title &amp; more
                Длительность: 1:02:03</pre>
            """.trimIndent(),
            actual,
        )
    }

    @Test
    fun usesFallbackWhenTitleIsMissing() {
        val actual = view.text(
            DownloadChoiceMediaInfo(
                channelName = "Channel",
                title = null,
                durationSeconds = null,
            ),
            BotLanguage.RU,
        )

        assertEquals("<pre>Название недоступно</pre>", actual)
    }

    @Test
    fun createsOneRowPerOptionWithFormattedSizes() {
        val token = UUID.randomUUID()
        val keyboard = view.keyboard(
            sessionToken = token,
            options = listOf(
                option("video_original", "Оригинал", 1_420_000_000, approximate = true),
                option("video_720", "720p", 460_000_000, approximate = false),
                option("audio", "Только звук", 24_500_000, approximate = true),
                option("cover", "Обложка", null, approximate = false),
            ),
            language = BotLanguage.RU,
        ).inlineKeyboard()

        val englishKeyboard = view.keyboard(
            sessionToken = token,
            options = listOf(option("video_original", "Original", 1_420_000_000, approximate = true)),
            language = BotLanguage.EN,
        ).inlineKeyboard()

        assertEquals(
            listOf(
                "🎬 Оригинал · ≈ 1,42 ГБ",
                "🎬 720p · 460 МБ",
                "🎧 Только звук · ≈ 24,5 МБ",
                "🖼 Обложка",
            ),
            keyboard.map { it.single().text },
        )
        assertEquals("🎬 Original · ≈ 1.42 GB", englishKeyboard.single().single().text)
        assertEquals(
            listOf("video_original", "video_720", "audio", "cover"),
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
            spec = DownloadSpec(
                originalUrl = URL,
                normalizedUrl = URL,
                cacheKey = "cache:$key",
                outputType = when (key) {
                    "audio" -> OutputType.AUDIO
                    "cover" -> OutputType.COVER
                    else -> OutputType.VIDEO
                },
                platform = if (key == "cover") {
                    DownloadPlatform.YOUTUBE
                } else {
                    DownloadPlatform.YOUTUBE
                },
                presetName = key,
                formatSelector = "best",
            ),
        )
    }

    private companion object {
        private const val URL = "https://example.com/video"
    }
}
