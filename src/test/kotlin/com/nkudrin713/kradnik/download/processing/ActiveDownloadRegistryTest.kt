package com.nkudrin713.kradnik.download.processing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveDownloadRegistryTest {
    @Test
    fun cancelsRegisteredExecutionAndRemovesIt() = runTest {
        val registry = ActiveDownloadRegistry()
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val execution = launch {
            registry.run(1) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cleaned.complete(Unit)
                }
            }
        }
        started.await()

        assertTrue(registry.cancel(1))
        cleaned.await()
        execution.join()
        assertFalse(registry.cancel(1))
    }
}
