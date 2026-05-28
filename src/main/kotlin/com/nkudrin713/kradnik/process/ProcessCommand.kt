package com.nkudrin713.kradnik.process

import java.io.File
import kotlin.time.Duration

data class ProcessCommand(
    val executable: String,
    val args: List<String>,
    val workingDir: File?,
    val timeout: Duration
)