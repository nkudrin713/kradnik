package com.nkudrin713.kradnik.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlExtractorTest {
    private val extractor = UrlExtractor()

    @Test
    fun `extracts first url`() {
        val actual = extractor.extract("download https://example.com/video now")

        assertEquals("https://example.com/video", actual)
    }

    @Test
    fun `trims punctuation`() {
        val actual = extractor.extract("https://example.com/video.")

        assertEquals("https://example.com/video", actual)
    }

    @Test
    fun `returns null when url is missing`() {
        val actual = extractor.extract("hello")

        assertNull(actual)
    }
}
