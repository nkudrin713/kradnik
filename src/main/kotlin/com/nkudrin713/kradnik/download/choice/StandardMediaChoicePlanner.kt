package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.MediaMetadata
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.telegram.localization.BotLanguage
import org.springframework.stereotype.Component

@Component
class StandardMediaChoicePlanner(private val choices: MediaChoiceBuilder) {
    fun options(specs: PlatformDownloadSpecs, metadata: MediaMetadata, language: BotLanguage): List<DownloadChoiceOptionSnapshot> = buildList {
        addAll(choices.videoOptions(specs.video, metadata, language))
        choices.audioOption(specs.audio, metadata, language)?.let(::add)
        choices.coverOption(specs.video, metadata, language)?.let(::add)
    }
}
