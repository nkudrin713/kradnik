package com.nkudrin713.kradnik.download.source

import com.nkudrin713.kradnik.download.domain.DownloadFailure
import com.nkudrin713.kradnik.download.domain.DownloadFailureReason
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpMediaDownloaderTest {
    @Test
    fun reportsTimeoutAsSourceFailureInsteadOfCancellingTheJob(@TempDir directory: Path) = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") {
            Thread.sleep(150)
            it.close()
        }
        server.start()
        try {
            val error = assertFailsWith<DownloadFailure> {
                HttpMediaDownloader().download(URI("http://127.0.0.1:${server.address.port}/slow"), directory.resolve("photo.jpg"), "image/", 10_000_000L, Duration.ofMillis(50))
            }
            assertEquals(DownloadFailureReason.SOURCE_TIMEOUT, error.reason)
        } finally {
            server.stop(0)
        }
    }
}
