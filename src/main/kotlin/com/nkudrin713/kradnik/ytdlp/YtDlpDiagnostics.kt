package com.nkudrin713.kradnik.ytdlp

import com.nkudrin713.kradnik.process.ProcessExecutionResult

/** Keeps the terminal error and extracts diagnostic signals without persisting the verbose trace. */
internal class YtDlpDiagnostics(private val result: ProcessExecutionResult, private val stage: String, private val args: List<String>) {
    val terminalError: String = result.stderr.lineSequence().lastOrNull { it.startsWith("ERROR:") || it.startsWith("yt-dlp: error:") }
        ?: result.stderr.trim().takeIf { '\n' !in it && '[' !in it && !it.startsWith("WARNING:") }.orEmpty()

    fun message(summary: String): String {
        val details = buildList {
            add("stage=$stage")
            result.exitCode?.let { add("exit=$it") }
            VERSION.find(result.stderr)?.groupValues?.get(1)?.let { add("version=$it") }
            args.zipWithNext().firstOrNull { (option, _) -> option == "-f" }
                ?.second?.takeIf(FORMAT_SELECTOR::matches)?.let { add("format=$it") }
            args.zipWithNext().firstNotNullOfOrNull { (option, value) ->
                value.takeIf { option == "--extractor-args" && it.startsWith("youtube:player_client=") }
                    ?.substringAfter('=')?.substringBefore(';')
            }?.takeIf(PLAYER_CLIENTS::matches)?.let { add("client=$it") }
            SELECTED_FORMAT.find(result.stderr)?.groupValues?.get(1)?.let { add("selected=$it") }
            if (result.stderrTruncated) add("stderr_truncated")
            val signals = SIGNALS.filterValues { it.containsMatchIn(result.stderr) }.keys
            if (signals.isNotEmpty()) add("signals=${signals.joinToString(",")}")
        }
        val safeError = if (SENSITIVE_DETAIL.containsMatchIn(terminalError)) {
            "[sensitive error details omitted]"
        } else {
            URL.replace(terminalError, "<url>").take(320)
        }
        val context = details.joinToString(", ", prefix = " [", postfix = "]")
        return if (safeError.isBlank()) summary + context else "$summary$context: $safeError"
    }

    private companion object {
        val VERSION = Regex("""\[debug] yt-dlp version ([\w-]{1,20}@\d{4}\.\d{2}\.\d{2}(?:\.\d{1,6})?)""")
        val FORMAT_SELECTOR = Regex("""[\w.,+*/<=>!^?\[\]-]{1,140}""")
        val PLAYER_CLIENTS = Regex("[a-z0-9_,]{1,60}")
        val SELECTED_FORMAT = Regex("""(?m)^\[info] [^\r\n]+: Downloading \d+ format\(s\): ([a-zA-Z0-9+._-]{1,80})[ \t]*$""")
        val URL = Regex("""(?:https?|socks[45]h?)://\S+""", RegexOption.IGNORE_CASE)
        val SENSITIVE_DETAIL = Regex("""(?:authorization|cookie|set-cookie|po[_ -]?token|visitor[_ -]?data)\s*[:=]""", RegexOption.IGNORE_CASE)
        val SIGNALS = mapOf(
            "gvs_token_retrieved" to Regex("""\[debug] \[youtube].*Retrieved a gvs PO Token"""),
            "gvs_token_missing" to Regex("""GVS PO Token which was not provided|Unable to fetch GVS PO Token"""),
            "token_provider_warning" to Regex("""(?m)^(?:WARNING|ERROR): .*\[pot(?::bgutil[^\]]*)?]"""),
            "sabr_formats_skipped" to Regex("""forcing SABR streaming|SABR-only streaming experiment"""),
            "signature_challenge_failed" to Regex("""challenge solving failed|signature extraction failed|nsig extraction failed"""),
            "images_only" to Regex("""Only images are available"""),
            "bot_check" to Regex("""confirm you.re not a bot""", RegexOption.IGNORE_CASE),
            "http_403" to Regex("""HTTP Error 403: Forbidden"""),
            "http_429" to Regex("""HTTP Error 429: Too Many Requests"""),
            "postprocessing" to Regex("""(?m)^ERROR: Postprocessing:"""),
        )
    }
}
