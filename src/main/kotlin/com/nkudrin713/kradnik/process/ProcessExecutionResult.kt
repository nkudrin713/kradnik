package com.nkudrin713.kradnik.process

/**
 * Captures the bounded outcome returned by [ProcessRunner] for one [Command].
 * Truncation flags mean an earlier output prefix was discarded; [diagnosticOutput] preserves that fact while placing
 * stderr before stdout for failure reporting.
 */
data class ProcessExecutionResult(
    val timedOut: Boolean,
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val workingDirectoryLimitExceeded: Boolean = false,
) {
    val diagnosticOutput: String
        get() = buildList {
            if (stdoutTruncated || stderrTruncated) {
                add("[process output truncated]")
            }
            addAll(listOf(stderr, stdout).filter(String::isNotBlank))
        }.joinToString(separator = "\n")
}
