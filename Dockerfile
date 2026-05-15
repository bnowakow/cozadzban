FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
ARG APP_BUILD_COMMIT=unknown

COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src src

RUN ./gradlew bootJar --no-daemon -PappBuildCommit="${APP_BUILD_COMMIT}"
RUN set -eux; \
    jar="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -print -quit)"; \
    cp "$jar" app.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=build /workspace/app.jar app.jar

USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
