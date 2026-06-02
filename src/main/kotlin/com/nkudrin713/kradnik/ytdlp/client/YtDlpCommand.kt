package com.nkudrin713.kradnik.ytdlp.client

import com.nkudrin713.kradnik.process.Command
import java.nio.file.Path
import kotlin.time.Duration

private const val YT_DLP = "yt-dlp"

data class YtDlpCommand(
    override val args: List<String>,
    override val workingDir: Path?,
    override val timeout: Duration,
    override val executable: String = YT_DLP,
) : Command
