package com.nkudrin713.kradnik.process

import kotlin.time.Duration

data class ProcessExecutionResult(
    val timedOut: Boolean,
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val workingDirectoryLimitExceeded: Boolean = false,
    val duration: Duration,
) {
    val diagnosticOutput: String
        get() = buildList {
            if (stdoutTruncated || stderrTruncated) {
                add("[process output truncated]")
            }
            addAll(listOf(stderr, stdout).filter(String::isNotBlank))
        }.joinToString(separator = "\n")
}
