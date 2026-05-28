package com.nkudrin713.kradnik.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Service
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Service
class ProcessRunner {

    suspend fun run(command: ProcessCommand): ProcessExecutionResult = coroutineScope {
        val start = TimeSource.Monotonic.markNow()
        val process = ProcessBuilder(command.executable, *command.args.toTypedArray())
            .directory(command.workingDir)
            .redirectErrorStream(true)
            .start()

        val outputDeferred = async(Dispatchers.IO) {
            readOutput(process.inputStream)
        }

        val finished = withContext(Dispatchers.IO) {
            process.waitFor(command.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
        if (!finished) {
            process.destroy()
            withContext(Dispatchers.IO) {
                process.waitFor(2, TimeUnit.SECONDS)
            }
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }

        val exitCode = if (finished) process.exitValue() else null
        val duration = start.elapsedNow()
        val output = withTimeoutOrNull(2000.milliseconds) {
            outputDeferred.await()
        } ?: ""

        ProcessExecutionResult(
            timedOut = !finished,
            exitCode = exitCode,
            output = output,
            duration = duration,
        )
    }

    private fun readOutput(inputStream: InputStream): String {
        val output = StringBuilder()

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                output.appendLine(line)
            }
        }

        return output.toString()
    }
}
