package com.nkudrin713.kradnik.download

import com.nkudrin713.kradnik.download.instagram.InstagramSourceAdapter
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import com.nkudrin713.kradnik.download.threads.ThreadsSourceAdapter
import com.nkudrin713.kradnik.download.vk.VkSourceAdapter
import com.nkudrin713.kradnik.download.youtube.YouTubeSourceAdapter
import com.nkudrin713.kradnik.ytdlp.YtDlpService
import io.mockk.mockk

fun testPlatformResolver(ytDlp: YtDlpService = mockk()): PlatformResolver = PlatformResolver(
    listOf(
        YouTubeSourceAdapter(ytDlp),
        InstagramSourceAdapter(mockk(), ytDlp),
        ThreadsSourceAdapter(ytDlp),
        VkSourceAdapter(mockk(), mockk(), ytDlp),
    ),
)
