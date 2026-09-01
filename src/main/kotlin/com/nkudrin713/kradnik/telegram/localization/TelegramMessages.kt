package com.nkudrin713.kradnik.telegram.localization

import org.springframework.context.MessageSource
import org.springframework.stereotype.Component

@Component
class TelegramMessages(
    private val messageSource: MessageSource,
) {
    fun text(
        language: BotLanguage,
        message: TelegramMessage,
        vararg arguments: Any,
    ): String {
        return messageSource.getMessage(message.key, arguments, language.locale)
    }
}

enum class TelegramMessage(val key: String) {
    BOT_NAME("bot.name"),
    LANGUAGE_PROMPT("language.prompt"),
    LANGUAGE_NAME("language.name"),
    LANGUAGE_SELECTED("language.selected"),
    START_PROMPT("start.prompt"),
    LINK_REQUIRED("link.required"),
    HELP("help.text"),
    LEGAL("legal.text"),
    DONATION_UNAVAILABLE("donation.unavailable"),
    DONATION_MESSAGE("donation.message"),
    DONATION_PIN("donation.pin"),
    DONATION_BUTTON("donation.button"),
    COMMAND_START("command.start"),
    COMMAND_HELP("command.help"),
    COMMAND_LEGAL("command.legal"),
    COMMAND_DONATE("command.donate"),
    COMMAND_LANGUAGE("command.language"),
    STATUS_ANALYZING("status.analyzing"),
    STATUS_QUEUED("status.queued"),
    STATUS_DOWNLOADING("status.downloading"),
    STATUS_UPLOADING("status.uploading"),
    STATUS_REJECTED_TOO_LARGE("status.rejected-too-large"),
    STATUS_AUTHENTICATION_REQUIRED("status.authentication-required"),
    STATUS_SOURCE_UNAVAILABLE("status.source-unavailable"),
    STATUS_ERROR("status.error"),
    CHOICE_TITLE_UNAVAILABLE("choice.title-unavailable"),
    CHOICE_DURATION("choice.duration"),
    CHOICE_UNAVAILABLE("choice.unavailable"),
    CHOICE_ORIGINAL("choice.original"),
    CHOICE_AUDIO("choice.audio"),
    CHOICE_COVER("choice.cover"),
    CHOICE_SIZE_MB("choice.size.mb"),
    CHOICE_SIZE_GB("choice.size.gb"),
    CHOICE_SELECTED("choice.selected"),
    CHOICE_MENU_INVALID("choice.menu-invalid"),
    CHOICE_NOT_OWNER("choice.not-owner"),
    CHOICE_ALREADY_SELECTED("choice.already-selected"),
    CHOICE_OPTION_UNAVAILABLE("choice.option-unavailable"),
    ERROR_NO_OPTIONS("error.no-options"),
    ERROR_INSTAGRAM_RATE_LIMITED("error.instagram-rate-limited"),
    ERROR_INSTAGRAM_UNAVAILABLE("error.instagram-unavailable"),
    ERROR_SOURCE_UNAVAILABLE("error.source-unavailable"),
    ERROR_METADATA_UNAVAILABLE("error.metadata-unavailable"),
    ERROR_TOO_LARGE("error.too-large"),
    ERROR_UNSUPPORTED_PLATFORM("error.unsupported-platform"),
    ERROR_UNSUPPORTED_URL("error.unsupported-url"),
    ERROR_CHOICE_PREPARATION("error.choice-preparation"),
}
