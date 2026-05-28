package com.nkudrin713.kradnik.process

import kotlin.time.Duration

data class ProcessExecutionResult(
    val timedOut: Boolean,
    val exitCode: Int?,
    val output: String = "",
    val duration: Duration
)