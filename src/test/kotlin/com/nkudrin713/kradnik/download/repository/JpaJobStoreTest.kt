package com.nkudrin713.kradnik.download.repository

import com.nkudrin713.kradnik.download.platform.DownloadPlatform
import com.nkudrin713.kradnik.download.source.SourceRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class JpaJobStoreTest {
    private val repository = mockk<DownloadJobRepository>()
    private val cache = JpaJobStore(repository)

    @Test
    fun cacheReadsTheSavedIdentityAndReturnsTheReceiptVerbatim() {
        val receipt = "post:v2:{\"text\":\"Saved text\",\"items\":[]}"
        every { repository.findCachedCompletedJob("unchanged") } returns DownloadJobEntity(telegramFileId = receipt)

        assertEquals(receipt, cache.find("unchanged"))

        verify(exactly = 1) { repository.findCachedCompletedJob("unchanged") }
    }

    @Test
    fun playlistCacheUsesTheSavedEncodingWithoutRebuildingSourceSettings() {
        val source = SourceRequest("url", "normalized", DownloadPlatform.YOUTUBE, "saved-format", listOf("--saved-arg", "value"), "saved-preset")
        every { repository.findCachedPlaylistFile("entry", "saved-preset", "saved-format", "[\"--saved-arg\",\"value\"]") } returns "cached-audio"

        assertEquals("cached-audio", cache.findPlaylistAudio("entry", source))

        verify(exactly = 1) { repository.findCachedPlaylistFile("entry", "saved-preset", "saved-format", "[\"--saved-arg\",\"value\"]") }
    }
}
