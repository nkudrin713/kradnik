# Kradnik

Telegram-бот для скачивания публичных видео из YouTube, Instagram и VK. Пользователь отправляет ссылку, выбирает качество видео, звук или обложку и получает файл ответом на исходное сообщение.

## Что умеет бот

- разбирает ссылки YouTube, Instagram и VK без общего fallback-механизма;
- показывает доступные варианты и примерный размер до создания задачи;
- скачивает видео, аудио и обложки;
- подготавливает видео под ограничения Telegram с помощью `ffmpeg`;
- повторно использует уже загруженный в Telegram `file_id`;
- хранит очередь, попытки, lease и короткоживущие сессии выбора в PostgreSQL;
- работает как с облачным Telegram Bot API, так и с локальным сервером для файлов до 2 ГБ;
- очищает временные каталоги и проверяет свободное место перед скачиванием.

Бот работает только с публичными одиночными публикациями. Плейлисты, закрытый контент и обход авторизации не поддерживаются.

## Как проходит запрос

```text
TelegramPollingService
  -> TelegramUpdateHandler
  -> DownloadChoiceCoordinator
  -> DownloadChoicePlanner + DownloadChoiceSessionService

выбор пользователя
  -> DownloadChoiceHandler
  -> TelegramDownloadStarter
  -> DownloadJobService
  -> DownloadQueueWorker
  -> DownloadJobProcessor
  -> DownloadEngine
  -> TelegramFileSender
```

Маршрут разделён на две части. До выбора бот определяет платформу, получает каталог форматов и сохраняет меню. После выбора создаётся задача в PostgreSQL; worker забирает её с lease, скачивает файл и отправляет его в Telegram.

## Где искать код

| Задача | Основные файлы |
| --- | --- |
| Приём команд и callback | `telegram/handler/TelegramUpdateHandler.kt`, `DownloadChoiceHandler.kt` |
| Меню качества, аудио и обложки | `download/choice/DownloadChoicePlanner.kt`, `telegram/TelegramDownloadChoiceView.kt` |
| Разбор ссылок и параметры платформ | `download/platform/*DownloadHandler.kt` |
| Скачивание источника | `download/DownloadEngine.kt`, `ytdlp/client/YtDlpService.kt` |
| Особенности Instagram | `download/instagram/` |
| Очередь, lease и повторы | `download/service/DownloadJobService.kt`, `download/processing/` |
| Отправка и лимиты Telegram | `telegram/TelegramMediaSender.kt`, `download/telegram/TelegramFileSender.kt`, `telegram/config/TelegramBotProperties.kt` |
| Подготовка видео | `download/video/` |
| Схема базы | `src/main/resources/db/migration/` |
| Контейнеры и деплой | `docker-compose.yml`, `.github/workflows/`, `scripts/render-deploy-env.sh` |

Чтобы добавить платформу, достаточно добавить значение в `DownloadPlatform`, реализацию `PlatformDownloadHandler` и тесты поддерживаемых URL. `PlatformResolver` получает обработчики через Spring. Параметры формата, нормализованный URL и cache key задаются в обработчике платформы.

## Локальный запуск

Нужны Java 21, Docker, `yt-dlp`, `ffmpeg` и токен Telegram-бота.

```bash
cp .env.example .env
# заполнить TELEGRAM_BOT_TOKEN в .env
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

Проверки:

```bash
./gradlew check
```

`check` запускает тесты и проверяет суммарное покрытие JaCoCo. Интеграционные тесты используют PostgreSQL через Testcontainers, поэтому Docker должен быть запущен.

## Конфигурация

Приложение не знает, является инстанс тестовым или production. Окружение передаёт конкретные адреса, токены, лимиты и пути через переменные среды.

Основные группы настроек:

- `POSTGRES_*` — подключение к PostgreSQL;
- `TELEGRAM_BOT_*`, `TELEGRAM_MAX_UPLOAD_BYTES` — Telegram API и лимит файла;
- `DOWNLOAD_WORK_DIR`, `DOWNLOAD_*_TIMEOUT` — рабочий каталог и таймауты;
- `DOWNLOAD_INSTAGRAM_RATE_LIMIT_*` — локальное ограничение запросов Instagram;
- `YOUTUBE_PO_TOKEN_PROVIDER_URL` — необязательный PO Token Provider для YouTube.

Локальный Telegram Bot API определяется по `TELEGRAM_BOT_API_URL` и `TELEGRAM_BOT_FILE_API_URL`. В этом режиме `DOWNLOAD_WORK_DIR` должен указывать на общий с контейнером Bot API volume.

## Docker и деплой

Образ содержит приложение, `yt-dlp`, `ffmpeg` и необходимые runtime-зависимости. Для локальной сборки полного стека:

```bash
./gradlew bootJar
mkdir -p .deploy
cp build/libs/app.jar .deploy/app.jar
docker build -t kradnik:local .
APP_IMAGE=kradnik:local docker compose up -d
```

Compose-профили `telegram-local` и `youtube-pot` включают необязательные сервисы. В GitHub Actions ветка `develop` разворачивается в test-окружение, а `main` используется для release/deploy production. Значения окружений остаются в GitHub Environments и server-side `.env`, а не в Kotlin-коде.

Миграции базы выполняет Flyway. Уже применённые миграции не изменяются; любое изменение схемы добавляется новым файлом.
