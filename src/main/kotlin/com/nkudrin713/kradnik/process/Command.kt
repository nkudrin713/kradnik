package com.nkudrin713.kradnik.process

import java.nio.file.Path
import kotlin.time.Duration

/**
 * Describes an external process for [ProcessRunner] as an executable and argument vector without shell interpretation.
 * It also carries the working directory, timeout, and optional workspace-growth guard used during execution.
 */
interface Command {
    val executable: String
    val args: List<String>
    val workingDir: Path?
    val timeout: Duration
    /** Optional hard growth limit enforced by [DefaultProcessRunner] against [workingDir] while the process is alive. */
    val maxWorkingDirectoryBytes: Long?
        get() = null
}
