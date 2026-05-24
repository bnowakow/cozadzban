// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

import java.time.Instant
import java.util.concurrent.TimeUnit

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.vaadin") version "25.1.4"
}

group = "pl.bnowakowski"
version = "0.55.2-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["vaadinVersion"] = "25.1.4"

dependencies {
	implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-batch")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.jsoup:jsoup:1.18.3")
	implementation("org.seleniumhq.selenium:selenium-java:4.32.0")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-zipkin")
	developmentOnly("com.vaadin:vaadin-dev")
	implementation("com.vaadin:vaadin-spring-boot-starter")
	implementation("io.micrometer:micrometer-tracing-bridge-brave")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
	implementation("tools.jackson.module:jackson-module-kotlin")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
	testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-batch-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-zipkin-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
	imports {
		mavenBom("com.vaadin:vaadin-bom:${property("vaadinVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty("app.facebook-import.enabled", "false")
	systemProperty("app.facebook-import.schedule.enabled", "false")
	systemProperty("app.facebook-import.headless", "true")
	systemProperty("vaadin.launch-browser", "false")
	if (!isDockerAvailable()) {
		logger.lifecycle("Docker is not available; excluding Docker-backed integration tests.")
		exclude("**/*IT.class", "**/CozadzbanApplicationTests.class")
	}
}

fun isDockerAvailable(): Boolean {
	val candidates = listOf("docker", "/opt/homebrew/bin/docker", "/usr/local/bin/docker")
	return candidates.any { command ->
		runCatching {
			val process = ProcessBuilder(command, "info")
				.redirectErrorStream(true)
				.start()
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly()
				false
			} else {
				process.exitValue() == 0
			}
		}.getOrDefault(false)
	}
}

tasks.named<ProcessResources>("processResources") {
	val timestamp = Instant.now().toString()
	val versionName = project.version.toString().removeSuffix("-SNAPSHOT")
	val commit = explicitBuildCommit() ?: currentGitCommit()
	inputs.property("appBuildTimestamp", timestamp)
	inputs.property("appBuildVersion", versionName)
	inputs.property("appBuildCommit", commit)
	filesMatching("application.properties") {
		filter { line ->
			line
				.replace("\${appBuildTimestamp}", timestamp)
				.replace("\${appBuildVersion}", versionName)
				.replace("\${appBuildCommit}", commit)
		}
	}
}

fun explicitBuildCommit(): String? =
	providers.gradleProperty("appBuildCommit")
		.orElse(providers.environmentVariable("APP_BUILD_COMMIT"))
		.orNull
		?.trim()
		?.takeIf { it.isNotBlank() && it != "unknown" }

fun currentGitCommit(): String =
	runCatching {
		val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
			.directory(rootDir)
			.redirectErrorStream(true)
			.start()
		if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
			"unknown"
		} else {
			process.inputStream.bufferedReader().readText().trim().ifBlank { "unknown" }
		}
	}.getOrDefault("unknown")
