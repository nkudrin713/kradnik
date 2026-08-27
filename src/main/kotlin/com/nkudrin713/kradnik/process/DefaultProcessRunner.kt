package com.nkudrin713.kradnik.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Executes [Command] directly and returns bounded stdout, stderr, exit, timeout, and workspace-limit state in
 * [ProcessExecutionResult]. The complete process tree is terminated on timeout, coroutine cancellation, or limit
 * breach, and output streams are drained without allowing diagnostics to grow without bound.
 */
@Service
class DefaultProcessRunner : ProcessRunner {
    override suspend fun run(command: Command): ProcessExecutionResult = coroutineScope {
        val process = ProcessBuilder(command.executable, *command.args.toTypedArray())
            .directory(command.workingDir?.toFile())
            .start()

        val stdoutDeferred = async(Dispatchers.IO) {
            readOutput(process.inputStream)
        }
        val stderrDeferred = async(Dispatchers.IO) {
            readOutput(process.errorStream)
        }
        val workingDirectoryLimitExceeded = AtomicBoolean(false)
        val workingDirectoryMonitor = startWorkingDirectoryMonitor(
            process = process,
            command = command,
            limitExceeded = workingDirectoryLimitExceeded,
        )

        try {
            val finished = runInterruptible(Dispatchers.IO) {
                process.waitFor(command.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
            if (!finished) {
                terminateProcessTree(process)
            }

            val capturedStreams = if (finished) {
                stdoutDeferred.await() to stderrDeferred.await()
            } else {
                withTimeoutOrNull(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS.milliseconds) {
                    stdoutDeferred.await() to stderrDeferred.await()
                } ?: (CapturedOutput.EMPTY to CapturedOutput.EMPTY)
            }

            ProcessExecutionResult(
                timedOut = !finished,
                exitCode = if (finished) process.exitValue() else null,
                stdout = capturedStreams.first.value,
                stderr = capturedStreams.second.value,
                stdoutTruncated = capturedStreams.first.truncated,
                stderrTruncated = capturedStreams.second.truncated,
                workingDirectoryLimitExceeded = workingDirectoryLimitExceeded.get(),
            )
        } finally {
            workingDirectoryMonitor?.cancelAndJoin()
            terminateProcessTree(process)
            stdoutDeferred.cancel()
            stderrDeferred.cancel()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.startWorkingDirectoryMonitor(
        process: Process,
        command: Command,
        limitExceeded: AtomicBoolean,
    ) = command.maxWorkingDirectoryBytes?.let { maxBytes ->
        require(maxBytes > 0) { "maxWorkingDirectoryBytes must be positive" }
        val workingDir = requireNotNull(command.workingDir) {
            "workingDir is required when maxWorkingDirectoryBytes is set"
        }

        launch(Dispatchers.IO) {
            while (isActive && process.isAlive) {
                if (runCatching { directorySizeExceeds(workingDir, maxBytes) }.getOrDefault(false)) {
                    limitExceeded.set(true)
                    terminateProcessTree(process)
                    return@launch
                }
                delay(WORKING_DIRECTORY_POLL_MS)
            }
        }
    }

    private fun directorySizeExceeds(directory: Path, maxBytes: Long): Boolean {
        if (!Files.exists(directory)) {
            return false
        }

        var totalBytes = 0L
        Files.walk(directory).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (!Files.isRegularFile(path)) {
                    continue
                }
                val size = Files.size(path)
                if (size > maxBytes - totalBytes) {
                    return true
                }
                totalBytes += size
            }
        }
        return false
    }

    private suspend fun terminateProcessTree(process: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            val root = process.toHandle()
            val descendants = root.descendants().toList().asReversed()
            val handles = descendants + root

            handles.filter { it.isAlive }.forEach { it.destroy() }
            waitForExit(handles, PROCESS_STOP_GRACE_PERIOD)
            handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
            waitForExit(handles, PROCESS_FORCE_STOP_PERIOD)
        }
    }

    private fun waitForExit(handles: List<ProcessHandle>, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (handles.any { it.isAlive } && System.nanoTime() < deadline) {
            Thread.sleep(PROCESS_EXIT_POLL_MS)
        }
    }

    private fun readOutput(inputStream: InputStream): CapturedOutput {
        val chunks = ArrayDeque<String>()
        val buffer = CharArray(OUTPUT_READ_BUFFER_CHARS)
        var capturedChars = 0
        var totalChars = 0L

        inputStream.reader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) {
                    break
                }

                totalChars = if (Long.MAX_VALUE - totalChars < count) {
                    Long.MAX_VALUE
                } else {
                    totalChars + count
                }
                chunks.addLast(buffer.concatToString(startIndex = 0, endIndex = count))
                capturedChars += count
                while (capturedChars > MAX_CAPTURED_OUTPUT_CHARS && chunks.size > 1) {
                    capturedChars -= chunks.removeFirst().length
                }
            }
        }

        return CapturedOutput(
            value = buildString(capturedChars) {
                chunks.forEach { append(it) }
            },
            truncated = totalChars > capturedChars,
        )
    }

    private companion object {
        private val PROCESS_STOP_GRACE_PERIOD = Duration.ofSeconds(2)
        private val PROCESS_FORCE_STOP_PERIOD = Duration.ofSeconds(2)
        private const val MAX_CAPTURED_OUTPUT_CHARS = 4 * 1024 * 1024
        private const val OUTPUT_READ_BUFFER_CHARS = 8 * 1024
        private const val PROCESS_OUTPUT_DRAIN_TIMEOUT_MS = 2_000L
        private const val PROCESS_EXIT_POLL_MS = 20L
        private const val WORKING_DIRECTORY_POLL_MS = 250L
    }
}

private data class CapturedOutput(
    val value: String,
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = CapturedOutput(value = "", truncated = false)
    }
}
