package com.nkudrin713.kradnik.download.platform

import com.nkudrin713.kradnik.download.domain.DownloadSpec
import com.nkudrin713.kradnik.download.source.SourceAdapter
import org.springframework.stereotype.Service

class UnsupportedPlatformException(message: String) : RuntimeException(message)

/** Registers sources once; URL syntax and saved download settings belong to the adapters. */
@Service
class PlatformResolver(adapters: List<SourceAdapter>) {
    private val adapters = adapters.associateBy { it.platform }

    init {
        require(this.adapters.size == adapters.size) { "Duplicate source adapters" }
    }

    fun platformOf(url: String): DownloadPlatform = adapters.values.firstOrNull { it.supports(url) }?.platform
        ?: throw UnsupportedPlatformException("Unsupported platform")

    fun resolve(url: String): PlatformDownloadSpecs = adapter(platformOf(url)).resolve(url)
    fun adapter(platform: DownloadPlatform): SourceAdapter = adapters.getValue(platform)
}

data class PlatformDownloadSpecs(val video: DownloadSpec, val audio: DownloadSpec)
