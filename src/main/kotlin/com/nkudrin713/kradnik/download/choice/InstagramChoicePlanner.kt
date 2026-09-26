package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.domain.OutputType
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessage
import com.nkudrin713.kradnik.telegram.localization.TelegramMessages
import org.springframework.stereotype.Component

@Component
class InstagramChoicePlanner(private val choices: MediaChoiceBuilder, private val messages: TelegramMessages) {
    fun options(specs: PlatformDownloadSpecs, metadata: MediaMetadata, language: BotLanguage): List<DownloadChoiceOptionSnapshot> {
        val post = postOption(specs.video, metadata.description, language)
        if (metadata.contentType != MediaContentType.VIDEO) return listOf(post)
        return buildList {
            add(post)
            addAll(choices.videoOptions(specs.video, metadata, language, allowUnknownOriginalSize = true))
            choices.audioOption(specs.audio, metadata, language)?.let(::add)
            choices.coverOption(specs.video, metadata, language)?.let(::add)
        }
    }

    private fun postOption(spec: DownloadSpec, postText: String?, language: BotLanguage): DownloadChoiceOptionSnapshot {
        return choices.option(
            spec = spec.copy(
                outputType = OutputType.POST,
                presetName = "${spec.platform.dbValue}_post",
                postText = postText?.takeIf(String::isNotBlank),
            ),
            key = POST_KEY,
            label = messages.text(language, TelegramMessage.CHOICE_POST),
            sizeBytes = null,
            approximateSize = false,
            language = language,
        )
    }

    private companion object {
        const val POST_KEY = "post"
    }
}
