package com.nkudrin713.kradnik.process

/** Executes an external command and returns its bounded diagnostic output. */
interface ProcessRunner {
    suspend fun run(command: Command): ProcessExecutionResult
}
