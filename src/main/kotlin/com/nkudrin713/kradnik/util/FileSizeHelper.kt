package com.nkudrin713.kradnik.util

fun byteToMB(fileSizeBytes: Long): Long =
    fileSizeBytes / 1024 / 1024

private const val metadataEpsilon = 1.05

fun estimateAudioSizeBytes(durationSeconds: Long, bitrateBps: Long): Long =
    (durationSeconds * bitrateBps / 8 * metadataEpsilon).toLong()

fun estimateVideoSizeBytes(durationSeconds: Long, videoBitrateBps: Long, audioBitrateBps: Long): Long =
    (durationSeconds * (videoBitrateBps + audioBitrateBps) / 8 * metadataEpsilon).toLong()
