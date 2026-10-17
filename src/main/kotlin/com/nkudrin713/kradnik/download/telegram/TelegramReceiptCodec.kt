package com.nkudrin713.kradnik.download.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nkudrin713.kradnik.download.domain.OutputType

/** Compatibility with existing download_jobs receipts; playlist completion markers are not media references. */
object TelegramReceiptCodec {
    private const val PHOTO_GROUP_PREFIX = "photo-group:"
    private const val POST_PREFIX = "post:v1:"
    private const val POST_CONTENT_PREFIX = "post:v2:"
    private val mapper = jacksonObjectMapper()

    fun decode(type: OutputType, value: String): CachedMedia {
        if (value.startsWith(POST_CONTENT_PREFIX)) return mapper.readValue<CachedMedia.Post>(value.removePrefix(POST_CONTENT_PREFIX)).copy(usesLegacyText = false)
        if (value.startsWith(POST_PREFIX)) return CachedMedia.Post(mapper.readValue(value.removePrefix(POST_PREFIX)))
        return when (type) {
            OutputType.VIDEO -> CachedMedia.Single(TelegramMediaKind.VIDEO, value)

            OutputType.AUDIO -> CachedMedia.Single(TelegramMediaKind.AUDIO, value)

            OutputType.COVER -> CachedMedia.Single(TelegramMediaKind.DOCUMENT, value)

            OutputType.IMAGES -> {
                require(value.startsWith(PHOTO_GROUP_PREFIX)) { "Cached photo group has invalid format" }
                CachedMedia.Photos(mapper.readValue(value.removePrefix(PHOTO_GROUP_PREFIX)))
            }

            OutputType.POST -> error("Cached post has invalid format")
        }
    }

    fun post(items: List<CachedPostItem>): String = POST_PREFIX + mapper.writeValueAsString(CachedMedia.Post(items).items)
    fun post(items: List<CachedPostItem>, text: String?): String = POST_CONTENT_PREFIX + mapper.writeValueAsString(CachedMedia.Post(items, text, usesLegacyText = false))
    fun photos(fileIds: List<String>): String = PHOTO_GROUP_PREFIX + mapper.writeValueAsString(fileIds)
    fun playlist(count: Int): String = "playlist:$count"
}
