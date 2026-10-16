package com.nkudrin713.kradnik.download.source

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import org.springframework.web.util.HtmlUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VkPostClientTest {
    private val mapper = jacksonObjectMapper()
    private val client = VkPostClient()

    @Test
    fun selectsExactStructuredPostInsteadOfRecommendationsOrReplies() {
        val other = """{"owner_id":-2,"id":3,"text":"Wrong post"}"""
        val expected = """{"owner_id":-1,"id":2,"text":"Full &amp; text<br>second line","attachments":[]}"""
        fun container(item: String): String {
            val data = HtmlUtils.htmlEscape("""{"PostContentContainer/init":{"item":$item}}""")
            return """<div data-exec="$data"></div>"""
        }
        val html = container(other) + "<div data-exec=\"not-json\"></div>" + container(expected) + container(other)
        val response = mapper.writeValueAsString(mapOf("payload" to listOf(0, listOf(false, html))))
        assertEquals(mapper.readTree(expected), client.parse(response, "-1_2"))
        assertFailsWith<DownloadFailure> { client.parse(response, "-9_2") }
    }

    @Test
    fun distinguishesAccessErrorsAndRejectsIncompleteResponses() {
        for ((code, reason) in listOf(3 to DownloadFailureReason.AUTHENTICATION_REQUIRED, 8 to DownloadFailureReason.SOURCE_UNAVAILABLE, 99 to DownloadFailureReason.METADATA_UNAVAILABLE)) {
            assertEquals(reason, assertFailsWith<DownloadFailure> { client.parse("{\"payload\":[$code,[]]}", "-1_2") }.reason)
        }
        assertEquals(DownloadFailureReason.METADATA_UNAVAILABLE, assertFailsWith<DownloadFailure> { client.parse("<html>login</html>", "-1_2") }.reason)
        assertFailsWith<DownloadFailure> { client.parse("{\"payload\":[0,[false,\"\"]]}", "-1_2") }
    }
}
