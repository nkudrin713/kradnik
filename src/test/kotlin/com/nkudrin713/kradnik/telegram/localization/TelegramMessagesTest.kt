package com.nkudrin713.kradnik.telegram.localization

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramMessagesTest {
    private val messages = telegramMessages()

    @Test
    fun resolvesEveryMessageInEverySupportedLanguage() {
        BotLanguage.entries.forEach { language ->
            TelegramMessage.entries.forEach { message ->
                assertTrue(messages.text(language, message, "value").isNotBlank())
            }
        }
    }

    @Test
    fun keepsEnglishAndRussianBundlesInSync() {
        val englishKeys = loadProperties("i18n/messages_en.properties").stringPropertyNames()
        val russianKeys = loadProperties("i18n/messages_ru.properties").stringPropertyNames()

        assertEquals(TelegramMessage.entries.map { it.key }.toSet(), englishKeys)
        assertEquals(englishKeys, russianKeys)
    }

    @Test
    fun parsesOnlySupportedLanguageCodes() {
        assertEquals(BotLanguage.RU, BotLanguage.fromCode("RU"))
        assertEquals(BotLanguage.EN, BotLanguage.fromCode("en"))
        assertEquals(null, BotLanguage.fromCode("de"))
        assertEquals(null, BotLanguage.fromCode(null))
    }

    private fun loadProperties(path: String): Properties {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(path))
        return Properties().apply {
            InputStreamReader(stream, StandardCharsets.UTF_8).use(::load)
        }
    }
}
