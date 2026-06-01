package com.nkudrin713.kradnik.process

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
        assertEquals("hello", result.output.trim())
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

        assertTrue(result.output.contains("error"))
    }

    companion object {
        @TempDir
        lateinit var tempDir: File
    }

}

private data class TestCommand(
    override val executable: String,
    override val args: List<String>,
    override val workingDir: File?,
    override val timeout: kotlin.time.Duration,
) : Command
