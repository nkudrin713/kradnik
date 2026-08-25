FROM eclipse-temurin:21-jre-alpine

ARG YT_DLP_VERSION=2026.07.04
ARG CURL_CFFI_VERSION=0.15.0
ARG BGUTIL_YTDLP_POT_PROVIDER_VERSION=1.3.2

WORKDIR /app

RUN apk add --no-cache ca-certificates deno=2.7.4-r2 ffmpeg py3-pip python3 \
    && pip install --no-cache-dir --break-system-packages \
        "yt-dlp[default]==${YT_DLP_VERSION}" \
        "bgutil-ytdlp-pot-provider==${BGUTIL_YTDLP_POT_PROVIDER_VERSION}" \
        "curl-cffi==${CURL_CFFI_VERSION}"

COPY .deploy/app.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
