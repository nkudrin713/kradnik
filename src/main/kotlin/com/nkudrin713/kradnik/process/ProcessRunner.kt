package com.nkudrin713.kradnik.process

/**
 * Executes a [Command] without shell interpretation and returns a bounded [ProcessExecutionResult].
 * [DefaultProcessRunner] supplies the production timeout, cancellation, process-tree, and workspace-limit behavior.
 */
interface ProcessRunner {
    suspend fun run(command: Command): ProcessExecutionResult
}
