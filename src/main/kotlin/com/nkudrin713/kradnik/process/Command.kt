package com.nkudrin713.kradnik.process

import java.nio.file.Path
import kotlin.time.Duration

/** An external process invocation expressed as an argument vector, without shell interpretation. */
interface Command {
    val executable: String
    val args: List<String>
    val workingDir: Path?
    val timeout: Duration
    /** Optional hard growth limit monitored while the process is alive. */
    val maxWorkingDirectoryBytes: Long?
        get() = null
}
