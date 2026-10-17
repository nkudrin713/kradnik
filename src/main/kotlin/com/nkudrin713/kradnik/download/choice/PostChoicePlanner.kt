package com.nkudrin713.kradnik.download.choice

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.domain.MediaContentType
import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.platform.PlatformDownloadSpecs
import com.nkudrin713.kradnik.download.source.MediaSelection
import com.nkudrin713.kradnik.download.source.PreparedPostSource
import com.nkudrin713.kradnik.localization.BotLanguage
import com.nkudrin713.kradnik.localization.MessageKey
import com.nkudrin713.kradnik.localization.Messages
import org.springframework.stereotype.Component

/** Owns post menu policy: Threads videos, full VK posts, and extra options for single Instagram videos. */
@Component
class PostChoicePlanner(private val choices: MediaChoiceBuilder, private val messages: Messages) {
    fun options(specs: PlatformDownloadSpecs, prepared: PreparedPostSource, language: BotLanguage): List<DownloadChoiceOptionSnapshot> {
        val metadata = prepared.metadata
        if (specs.video.platform == DownloadPlatform.THREADS) return StandardMediaChoicePlanner(choices).videosOnly(specs.video, metadata, language, messages)
        val post = postOption(specs.video, prepared.post.text, language)
        if (specs.video.platform != DownloadPlatform.INSTAGRAM || metadata.contentType != MediaContentType.VIDEO) return listOf(post)
        return buildList {
            add(post)
            addAll(choices.videoOptions(specs.video, metadata, language, allowUnknownOriginalSize = true))
            choices.audioOption(specs.audio, metadata, language)?.let(::add)
            choices.coverOption(specs.video, metadata, language)?.let(::add)
        }
    }

    private fun postOption(spec: DownloadSpec, postText: String?, language: BotLanguage): DownloadChoiceOptionSnapshot {
        return choices.option(
            spec = choices.select(spec, MediaSelection.Post(postText?.takeIf(String::isNotBlank))),
            key = POST_KEY,
            label = messages.text(language, MessageKey.CHOICE_POST),
            sizeBytes = null,
            approximateSize = false,
            language = language,
        )
    }

    private companion object {
        const val POST_KEY = "post"
    }
}
