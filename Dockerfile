# syntax=docker/dockerfile:1.7

FROM node:22-bookworm-slim AS codex-cli

# Pin the CLIs so image rebuilds cannot silently change their behavior.
RUN npm install --global --prefix=/opt/codex @openai/codex@0.164.0
# Claude Code draws SVG fallback illustrations when Codex can't produce an image.
RUN npm install --global --prefix=/opt/claude @anthropic-ai/claude-code@2.1.316

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
ARG APP_BUILD_COMMIT=unknown

COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY package.json package-lock.json tsconfig.json vite.config.ts vite.generated.ts types.d.ts ./
COPY src src

RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    --mount=type=cache,target=/root/.vaadin,sharing=locked \
    --mount=type=cache,target=/root/.npm,sharing=locked \
    ./gradlew bootJar --no-daemon -PappBuildCommit="${APP_BUILD_COMMIT}"
RUN set -eux; \
    jar="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -print -quit)"; \
    cp "$jar" app.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system spring && useradd --system --gid spring --home-dir /home/spring --create-home spring

COPY --from=build /workspace/app.jar app.jar
COPY --from=codex-cli /usr/local/bin/node /usr/local/bin/node
COPY --from=codex-cli /opt/codex /opt/codex
COPY --from=codex-cli /opt/claude /opt/claude

ENV HOME=/home/spring \
    CODEX_HOME=/home/spring/.codex \
    CLAUDE_CONFIG_DIR=/home/spring/.claude \
    DISABLE_AUTOUPDATER=1 \
    PATH="/opt/codex/bin:/opt/claude/bin:${PATH}"

RUN mkdir -p /home/spring/.codex /home/spring/.claude \
    && chown spring:spring /home/spring/.codex /home/spring/.claude

USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
