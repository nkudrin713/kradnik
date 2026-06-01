package com.nkudrin713.kradnik.util

fun byteToMB(fileSizeByte: Long): Long =
    fileSizeByte / 1024 / 1024

private const val metadataEpsilon = 1.05

fun estimateAudioSizeByte(durationSeconds: Long, bitrateBps: Long): Long =
    (durationSeconds * bitrateBps / 8 * metadataEpsilon).toLong()

fun estimateVideoSizeByte(durationSeconds: Long, videoBitrateBps: Long, audioBitrateBps: Long): Long =
    (durationSeconds * (videoBitrateBps + audioBitrateBps) / 8 * metadataEpsilon).toLong()
