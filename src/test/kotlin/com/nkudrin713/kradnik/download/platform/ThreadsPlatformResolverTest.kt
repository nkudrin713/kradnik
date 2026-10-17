package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.UnsupportedUrlException
import com.nkudrin713.kradnik.download.testPlatformResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThreadsPlatformResolverTest {
    private val resolver = testPlatformResolver()

    @Test
    fun aliasesAndTrackingShareOnePostIdentity() {
        val urls = listOf(
            "https://www.threads.com/@user/post/ABC_123?xmt=tracking",
            "https://threads.net/@another.user/post/ABC_123/",
            "https://www.threads.com/t/ABC_123",
        )
        val specs = urls.map { resolver.resolve(it).video }
        assertEquals(setOf("https://www.threads.com/t/ABC_123/"), specs.map { it.normalizedUrl }.toSet())
        assertEquals(1, specs.map { it.cacheKey }.distinct().size)
        assertEquals(setOf(DownloadPlatform.THREADS), specs.map { it.platform }.toSet())
        assertEquals("https://www.threads.com/share/SHARED/", resolver.resolve("https://threads.net/share/SHARED?xmt=x").video.normalizedUrl)
    }

    @Test
    fun rejectsProfilesExtraPathsAndUnexpectedOrigins() {
        listOf(
            "https://threads.com/@user",
            "https://threads.com/@user/post/ABC/extra",
            "https://threads.com/t/",
            "ftp://threads.com/t/ABC",
            "https://name@threads.com/t/ABC",
            "https://threads.com:8443/t/ABC",
        ).forEach { url -> assertFailsWith<UnsupportedUrlException>(url) { resolver.resolve(url) } }
        assertFailsWith<UnsupportedPlatformException> { resolver.resolve("https://threads.com.example.org/t/ABC") }
    }
}
