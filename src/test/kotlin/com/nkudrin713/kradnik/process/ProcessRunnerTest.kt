package com.nkudrin713.kradnik.process

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerTest {
    private val runner: ProcessRunner = DefaultProcessRunner()

    @Test
    fun `captures output`() = runTest {
        val result = runner.run(
            TestCommand(
                executable = "echo",
                args = listOf("hello"),
                workingDir = tempDir,
                timeout = 5.seconds,
            )
        )

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout.trim())
        assertEquals("", result.stderr)
    }

    @Test
    fun `times out`() = runTest {
        val result = runner.run(
            TestCommand(
                executable = "sleep",
                args = listOf("5"),
                workingDir = tempDir,
                timeout = 100.milliseconds,
            )
        )

        assertTrue(result.timedOut)
        assertNull(result.exitCode)
    }

    @Test
    fun `stderr captured`() = runTest {
        val result = runner.run(
            TestCommand(
                executable = "sh",
                args = listOf("-c", "echo error-message >&2"),
                workingDir = tempDir,
                timeout = 5.seconds,
            )
        )

        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("error"))
    }

    @Test
    fun `drains both streams concurrently and bounds captured output`() = runTest {
        val result = runner.run(
            TestCommand(
                executable = "sh",
                args = listOf(
                    "-c",
                    "yes stdout | head -c 5000000; yes stderr | head -c 131072 >&2",
                ),
                workingDir = tempDir,
                timeout = 10.seconds,
            )
        )

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stdout.length < 5_000_000)
        assertFalse(result.stderrTruncated)
        assertEquals(131_072, result.stderr.length)
    }

    @Test
    fun `cancellation terminates process tree`() = runBlocking {
        val rootPidFile = tempDir.resolve("root.pid")
        val childPidFile = tempDir.resolve("child.pid")
        val execution = async {
            runner.run(
                TestCommand(
                    executable = "sh",
                    args = listOf(
                        "-c",
                        "echo ${'$'}${'$'} > \"${'$'}1\"; sleep 30 & echo ${'$'}! > \"${'$'}2\"; wait",
                        "sh",
                        rootPidFile.toString(),
                        childPidFile.toString(),
                    ),
                    workingDir = tempDir,
                    timeout = 60.seconds,
                )
            )
        }
        withTimeout(5.seconds) {
            while (!Files.exists(rootPidFile) || !Files.exists(childPidFile)) {
                delay(20.milliseconds)
            }
        }
        val rootPid = Files.readString(rootPidFile).trim().toLong()
        val childPid = Files.readString(childPidFile).trim().toLong()

        execution.cancelAndJoin()

        assertFalse(ProcessHandle.of(rootPid).map(ProcessHandle::isAlive).orElse(false))
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
    }

    companion object {
        @TempDir
        lateinit var tempDir: Path
    }
}

private data class TestCommand(
    override val executable: String,
    override val args: List<String>,
    override val workingDir: Path?,
    override val timeout: kotlin.time.Duration,
) : Command
