FROM eclipse-temurin:21-jre-alpine

ARG YT_DLP_VERSION=2026.03.17

WORKDIR /app

RUN apk add --no-cache ca-certificates ffmpeg py3-pip python3 \
    && pip install --no-cache-dir --break-system-packages "yt-dlp==${YT_DLP_VERSION}"

COPY .deploy/app.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
