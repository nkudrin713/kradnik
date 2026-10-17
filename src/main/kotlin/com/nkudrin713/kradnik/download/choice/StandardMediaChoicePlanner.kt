package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
import org.springframework.stereotype.Component

@Component
class StandardMediaChoicePlanner(private val choices: MediaChoiceBuilder) {
    fun videosOnly(spec: DownloadSpec, metadata: MediaMetadata, language: BotLanguage, messages: Messages): List<DownloadChoiceOptionSnapshot> {
        if (metadata.contentType != MediaContentType.CAROUSEL) {
            return choices.videoOptions(spec, metadata, language, allowUnknownOriginalSize = true).ifEmpty {
                listOf(choices.option(spec, "video_original", messages.text(language, MessageKey.CHOICE_ORIGINAL), null, false, language = language))
            }
        }
        return listOf(
            choices.option(
                spec = choices.select(spec, MediaSelection.VideoCollection),
                key = "videos",
                label = messages.text(language, MessageKey.CHOICE_VIDEOS),
                sizeBytes = null,
                approximateSize = false,
                language = language,
            ),
        )
    }

    fun options(specs: PlatformDownloadSpecs, metadata: MediaMetadata, language: BotLanguage): List<DownloadChoiceOptionSnapshot> = buildList {
        addAll(choices.videoOptions(specs.video, metadata, language))
        choices.audioOption(specs.audio, metadata, language)?.let(::add)
        choices.coverOption(specs.video, metadata, language)?.let(::add)
    }
}
