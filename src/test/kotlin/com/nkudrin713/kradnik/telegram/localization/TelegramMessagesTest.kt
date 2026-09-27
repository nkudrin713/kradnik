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
        assertEquals(BotLanguage.RU_INFORMAL, BotLanguage.fromCode("ru-informal"))
        assertEquals(null, BotLanguage.fromCode("de"))
        assertEquals(null, BotLanguage.fromCode(null))
    }

    @Test
    fun informalBundleContainsEveryCurrentMessageWithMatchingArguments() {
        val english = loadProperties("i18n/messages_en.properties")
        val informal = loadProperties("i18n/messages_ru_RU_INFORMAL.properties")
        val argument = Regex("\\{\\d+}")
        english.stringPropertyNames().forEach { key ->
            val text = assertNotNull(informal.getProperty(key), key)
            assertTrue(text.isNotBlank(), key)
            assertEquals(
                argument.findAll(english.getProperty(key)).map { it.value }.toSet(),
                argument.findAll(text).map { it.value }.toSet(),
                key,
            )
        }
    }

    @Test
    fun resolvesInformalTextsWithoutChangingStandardRussian() {
        assertEquals("Свойский", messages.text(BotLanguage.RU_INFORMAL, TelegramMessage.LANGUAGE_NAME))
        assertEquals("Наш человек. Кидай ссылку", messages.text(BotLanguage.RU_INFORMAL, TelegramMessage.LANGUAGE_SELECTED))
        assertEquals("Принял: MP3", messages.text(BotLanguage.RU_INFORMAL, TelegramMessage.CHOICE_SELECTED, "MP3"))
        assertEquals("Русский", messages.text(BotLanguage.RU, TelegramMessage.LANGUAGE_NAME))
        assertEquals("Пришли ссылку на медиа", messages.text(BotLanguage.RU, TelegramMessage.START_PROMPT))
    }

    private fun loadProperties(path: String): Properties {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(path))
        return Properties().apply {
            InputStreamReader(stream, StandardCharsets.UTF_8).use(::load)
        }
    }
}
