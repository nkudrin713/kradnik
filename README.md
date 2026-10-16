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

Only public content available without signing in is supported.

## Stack

Kotlin, Java 21, Spring Boot, PostgreSQL, yt-dlp, FFmpeg, and Docker Compose.

## Self-hosting

Run your own instance with a Telegram bot token. Deployment configuration is provided in [Docker Compose](docker-compose.yml) and the [environment example](.env.example).
