package com.nkudrin713.kradnik.download.request

import com.nkudrin713.kradnik.download.domain.DownloadJob
import com.nkudrin713.kradnik.download.platform.PlatformResolver
import org.springframework.stereotype.Service

@Service
class DownloadRequestFactory(
    private val platformResolver: PlatformResolver,
) {
    fun create(job: DownloadJob): DownloadRequest {
        return platformResolver.resolve(job.originalUrl)
            .buildRequest(job.originalUrl, job.outputType)
    }
}
