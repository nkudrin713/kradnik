FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src src

RUN chmod +x gradlew
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

ARG YT_DLP_VERSION=2026.03.17

WORKDIR /app

RUN apk add --no-cache ca-certificates ffmpeg py3-pip python3 \
    && pip install --no-cache-dir --break-system-packages "yt-dlp==${YT_DLP_VERSION}"

COPY --from=build /app/build/libs/app.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
