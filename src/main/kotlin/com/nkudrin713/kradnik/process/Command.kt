package com.nkudrin713.kradnik.process

import java.nio.file.Path
import kotlin.time.Duration

interface Command {
    val executable: String
    val args: List<String>
    val workingDir: Path?
    val timeout: Duration
}
