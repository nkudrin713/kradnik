package com.nkudrin713.kradnik.telegram

import org.springframework.stereotype.Component

private val URL_REGEX = Regex("""https?://\S+""")

@Component
class UrlExtractor {
    fun extract(text: String): String? =
        URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']')
}
