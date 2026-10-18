# Kradnik

A Telegram bot for saving public videos, photos, and posts from social networks. Send a link, choose what to download, and receive the files in Telegram.

## Features

- Download videos from YouTube, Instagram, VK, and Threads with a choice of quality.
- Save audio as MP3 or download video covers where available.
- Save entire Instagram and VK posts with their text, photos, and videos.
- Download YouTube playlists as individual MP3 tracks or a ZIP archive, up to 100 tracks at a time.
- Track download progress, cancel requests, and retry failed downloads.
- Monitor bot activity and manage enabled sources through a web dashboard.

Use the bot in a private chat or inline in other chats with `@your_bot <link>`. Full posts and playlists require a private chat. The interface supports English and Russian, including an informal Russian mode.

Selecting a language with `/language` also updates command descriptions in the private chat's Menu, independently of the Telegram interface language.

Queue positions update automatically and appear on one line. Download errors briefly explain the problem and suggest what to do next.

Only public content available without signing in is supported.

VK post links are converted to plain text with their full URLs. Descriptive link labels are retained; repeated or shortened URL labels are omitted. Unrecognized markup is preserved.

## Download architecture

Source adapters extract metadata and download media. `SourcePost` contains text, author, and ordered media references; it carries no menu policy. `PostChoicePlanner` decides which post actions the bot offers, while `StandardMediaChoicePlanner` and `MediaChoiceBuilder` build individual media options and apply quality and size rules. Shared handlers produce the files; job processing owns cache lookup, delivery, and cleanup.

URL recognition, source settings, and download specifications live in the service packages: `download/youtube`, `download/instagram`, `download/vk`, and `download/threads`. `PlatformResolver` registers adapters through their common interface. Shared planning and media handlers use those contracts; yt-dlp command arguments are assembled outside the common workflows.

Execution depends on contracts for storage, delivery, cache, and notifications. JPA entities, saved JSON formats, and Telegram SDK types stay in their adapters. Job creation owns cache-key finalization; execution and retries use saved selections and typed failures. Architecture tests enforce the boundaries and exercise fixture sources with independent delivery implementations.

| Source | Supported actions |
| --- | --- |
| YouTube video; VK video or clip | Video quality options, audio, cover |
| YouTube playlist | Audio messages or ZIP; all tracks up to 100, otherwise the first or last 100 |
| Instagram single video | Full post, video quality options, audio, cover |
| Instagram photos or mixed carousel | Full post |
| VK wall post, including text-only posts | Full post |
| Threads single video | Video quality options |
| Threads video collection | Ordered video album |

Options depend on available metadata and upload limits. Audio requires a usable duration estimate; covers require a thumbnail. Posts and video albums retain up to 20 supported media items in source order, including duplicates. Full posts, video albums, and playlists require a private chat. Download selections are saved separately from temporary source preparation, so menu policy does not change an already saved selection.

## Stack

Kotlin, Java 21, Spring Boot, PostgreSQL, yt-dlp, FFmpeg, and Docker Compose.

## Self-hosting

Run your own instance with a Telegram bot token. Deployment configuration is provided in [Docker Compose](docker-compose.yml) and the [environment example](.env.example).

The responsive web dashboard opens with bot health and key activity metrics. Detailed worker, queue, memory, and GC diagnostics expand on demand; source switches and database backups are available in the system section.
