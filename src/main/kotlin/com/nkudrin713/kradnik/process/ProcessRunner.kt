package com.nkudrin713.kradnik.process

interface ProcessRunner {
    suspend fun run(command: Command): ProcessExecutionResult
}
