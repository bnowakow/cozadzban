
.PHONY: help docker-up docker-down docker-data-permissions docker-upgrade-log-owner docker-pg-nuke docker-pg-backup install-pg-backup-cron ensure-pg-backup-cron install-codex-auth-cron install-app-health-cron install-all-crons codex-auth-check app-health-check build run run-local run-prod test clean docker-logs docker-spring-shell docker-pg-shell docker-upgrade docker-upgrade-no-cache sync-env-files bump-patch bump-minor codex-commit claude-commit install-git-hooks install-codex-skills install-claude-skills codex-skill-prompts claude-skill-prompts codex-docker-login codex-docker-status codex-docker-logout

-include .env

# Default target
help:
	@printf "CoZaDzban — Makefile targets\n"
	@printf "\n"
	@printf "  PostgreSQL port from .env: $(POSTGRES_PORT)\n"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "Docker"
	@printf "    %-31s %s\n" "docker-up" "Start local infrastructure from compose.yaml"
	@printf "    %-31s %s\n" "docker-down" "Stop and remove local infrastructure containers"
	@printf "    %-31s %s\n" "docker-logs" "Show compose logs (follow mode)"
	@printf "    %-31s %s\n" "docker-spring-shell" "Open bash inside the running Spring Boot container"
	@printf "    %-31s %s\n" "docker-upgrade" "Pull latest code, rebuild image, and switch Spring traffic after health check"
	@printf "    %-31s %s\n" "docker-upgrade-no-cache" "Pull latest code, rebuild without Docker cache, and switch after health check"
	@printf "    %-31s %s\n" "codex-docker-login" "Sign in to Codex in Spring Boot with a browser device code"
	@printf "    %-31s %s\n" "codex-docker-status" "Check Codex sign-in in the Spring Boot container"
	@printf "    %-31s %s\n" "codex-docker-logout" "Sign out of Codex in the Spring Boot container"
	@printf "    %-31s %s\n" "codex-auth-check" "Check cached login and make a small live Codex request"
	@printf "    %-31s %s\n" "app-health-check" "Check the active app and restart it after repeated failures"
	@printf "    %-31s %s\n" "sync-env-files" "Sync server and worker env files, then verify byte-for-byte"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "PostgreSQL in Docker"
	@printf "    %-31s %s\n" "docker-pg-nuke" "Recreate PostgreSQL container and reset docker-data/postgres"
	@printf "    %-31s %s\n" "docker-pg-backup" "Dump PostgreSQL and zip it into docker-data/backup/postgres"
	@printf "    %-31s %s\n" "install-pg-backup-cron" "Install daily 02:00 PostgreSQL backup cron job"
	@printf "    %-31s %s\n" "ensure-pg-backup-cron" "Install daily PostgreSQL backup cron job only when missing"
	@printf "    %-31s %s\n" "install-codex-auth-cron" "Install daily Codex authorization check at 03:15"
	@printf "    %-31s %s\n" "install-app-health-cron" "Install app health check every minute"
	@printf "    %-31s %s\n" "install-all-crons" "Install all managed cron jobs"
	@printf "    %-31s %s\n" "docker-pg-shell" "Open PostgreSQL shell inside docker container"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "Application"
	@printf "    %-31s %s\n" "build" "Build the project (skip tests)"
	@printf "    %-31s %s\n" "run" "Run Spring Boot with SPRING_PROFILES_ACTIVE from .env"
	@printf "    %-31s %s\n" "run-local" "Run Spring Boot with local profile"
	@printf "    %-31s %s\n" "run-prod" "Run Spring Boot with prod profile"
	@printf "    %-31s %s\n" "test" "Run all tests"
	@printf "    %-31s %s\n" "clean" "Clean Gradle build artifacts"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "Versioning"
	@printf "    %-31s %s\n" "bump-patch" "Auto-increment patch for x.y.z-SNAPSHOT versions"
	@printf "    %-31s %s\n" "bump-minor" "Auto-increment minor and reset patch for x.y.z-SNAPSHOT versions"
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "Repository"
	@printf "    %-31s %s\n" "codex-commit" "Bump version if needed, stage, commit with Codex, and optionally push"
	@printf "    %-31s %s\n" "claude-commit" "Bump version if needed, stage, commit with Claude, and optionally push"
	@printf "    %-31s %s\n" "install-git-hooks" "Configure repository git hooks"
	@printf "    %-31s %s\n" "install-codex-skills" "Install all project Codex skills into CODEX_HOME"
	@printf "    %-31s %s\n" "install-claude-skills" "Install all project skills into Claude Code's CLAUDE_CONFIG_DIR"
	@printf "    %-31s %s\n" "codex-skill-prompts" "Show sample prompts for project Codex skills"
	@printf "    %-31s %s\n" "claude-skill-prompts" "Show slash commands and sample prompts for project Claude skills"
	@printf "\n"

# Active Spring profile used by the generic run target.
PROFILE ?= $(SPRING_PROFILES_ACTIVE)
PROFILE ?= local
POSTGRES_PORT ?= 5432
APP_PORT ?= 8086
LOCAL_UID ?= $(shell id -u)
LOCAL_GID ?= $(shell id -g)
APP_BUILD_COMMIT ?= $(shell git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)
CODEX_HOME ?= $(HOME)/.codex
CLAUDE_CONFIG_DIR ?= $(HOME)/.claude
SDKMAN_JAVA_HOME ?= $(HOME)/.sdkman/candidates/java/current
ifneq ($(wildcard $(SDKMAN_JAVA_HOME)/bin/java),)
JAVA_HOME ?= $(SDKMAN_JAVA_HOME)
endif
GRADLE_USER_HOME ?= /tmp/cozadzban-gradle-home
TEST_GRADLE_ARGS ?=
TEST_GRADLE_WORKERS ?=
TEST_GRADLE_JVMARGS ?=
COZADZBAN_TEST_MAX_PARALLEL_FORKS ?=
COZADZBAN_TEST_TIMEOUT_MINUTES ?=
COZADZBAN_DOCKER_INFO_TIMEOUT_SECONDS ?=
CRON_SCHEDULE ?= 0 2 * * *
CODEX_AUTH_CRON_SCHEDULE ?= 15 3 * * *
APP_HEALTH_CRON_SCHEDULE ?= * * * * *
CRON_MAKE ?= $(shell command -v make 2>/dev/null || echo make)
PG_BACKUP_CRON_MARKER ?= cozadzban-docker-pg-backup
PG_BACKUP_CRON_LEGACY_MARKER ?= cozazjeb-docker-pg-backup
PG_BACKUP_CRON_MARKERS := $(PG_BACKUP_CRON_MARKER) $(PG_BACKUP_CRON_LEGACY_MARKER)
ENV_SYNC_HOST ?= ovh.bnowakowski.pl
ENV_SYNC_DIR ?= /home/sup/docker/cozadzban.pl
export APP_BUILD_COMMIT JAVA_HOME GRADLE_USER_HOME COZADZBAN_TEST_MAX_PARALLEL_FORKS COZADZBAN_TEST_TIMEOUT_MINUTES COZADZBAN_DOCKER_INFO_TIMEOUT_SECONDS

# Start local development environment from compose.yaml
docker-up: docker-data-permissions
	@printf 'server springboot:8080 max_fails=3 fail_timeout=10s;\n' > ./docker-data/nginx/upstream.conf
	docker compose -f compose.yaml up -d postgres zipkin springboot reverse-proxy
	@echo ""
	@echo "✓ Services started:"
	@echo "  - PostgreSQL:     localhost:$(POSTGRES_PORT)"
	@echo "  - Reverse proxy:  http://localhost:$(APP_PORT)"
	@echo ""

# Prepare docker-data so containers can persist app data and host cron backups can write backup files.
docker-data-permissions:
	@mkdir -p ./docker-data
	@mkdir -p ./logs
	@chmod -R a+rwX ./logs
	@docker run --rm -v "$(CURDIR)/docker-data:/work" alpine:3.20 \
		sh -c "mkdir -p /work/postgres /work/data/favicons /work/backup/postgres /work/nginx && if [ ! -f /work/nginx/upstream.conf ]; then printf 'server springboot:8080 max_fails=3 fail_timeout=10s;\n' > /work/nginx/upstream.conf; fi && chown -R $(LOCAL_UID):$(LOCAL_GID) /work/backup /work/nginx && chmod 755 /work /work/postgres /work/nginx && chmod -R a+rwX /work/data && chmod -R u+rwX,go-rwx /work/backup && chmod 644 /work/nginx/upstream.conf"

docker-upgrade-log-owner:
	@mkdir -p ./logs
	@if ! chmod -R a+rwX ./logs 2>/dev/null; then \
		echo "! ./logs contains files this user cannot chmod; taking ownership with sudo"; \
		sudo chown sup:sup -R logs; \
	fi

# Stop local development environment
docker-down:
	docker compose -f compose.yaml down

# Use a one-off container from the Spring Boot image, so sign-in works before
# either blue or green container is replaced. Both use the same auth volume.
# Device sign-in prints a browser URL and one-time code in this terminal.
codex-docker-login:
	@bash utilities/codex-docker-login.sh

codex-docker-status:
	docker compose -f compose.yaml run --rm --no-deps --entrypoint codex springboot login status

codex-docker-logout:
	docker compose -f compose.yaml run --rm --no-deps --entrypoint codex springboot logout

codex-auth-check:
	@bash utilities/check-codex-docker-auth.sh

app-health-check:
	@APP_PORT='$(APP_PORT)' bash utilities/check-app-health.sh

# Recreate PostgreSQL container with a fresh data directory
docker-pg-nuke:
	$(MAKE) docker-down
	@echo "Resetting ./docker-data/postgres ..."
	@if rm -rf ./docker-data/postgres 2>/dev/null; then \
		echo "✓ Removed postgres data directory"; \
	else \
		echo "! Host cleanup failed (likely root-owned files), using containerized cleanup"; \
		docker run --rm -v "$(CURDIR)/docker-data:/work" alpine:3.20 \
			sh -c "rm -rf /work/postgres && mkdir -p /work/postgres && chown -R $(LOCAL_UID):$(LOCAL_GID) /work/postgres"; \
	fi
	@mkdir -p ./docker-data/postgres
	$(MAKE) docker-up

# Dump PostgreSQL from the running compose container and store a timestamped zip.
docker-pg-backup: docker-data-permissions
	@mkdir -p ./docker-data/backup/postgres
	@timestamp=$$(date +"%Y-%m-%d_%H-%M-%S"); \
	base="cozadzban-postgres-$$timestamp"; \
	sql_path="./docker-data/backup/postgres/$$base.sql"; \
	zip_path="./docker-data/backup/postgres/$$base.zip"; \
	echo "Dumping PostgreSQL database '$(POSTGRES_DB)' to $$sql_path ..."; \
	docker compose -f compose.yaml exec -T postgres pg_dump -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)" --clean --if-exists > "$$sql_path"; \
	zip -j "$$zip_path" "$$sql_path" >/dev/null; \
	rm "$$sql_path"; \
	echo "✓ Backup written to $$zip_path"

# Install or replace the current user's daily PostgreSQL backup cron job.
install-pg-backup-cron: docker-data-permissions
	@CRON_SCHEDULE='$(CRON_SCHEDULE)' CRON_MAKE='$(CRON_MAKE)' bash utilities/install-crons.sh postgres

install-codex-auth-cron:
	@CODEX_AUTH_CRON_SCHEDULE='$(CODEX_AUTH_CRON_SCHEDULE)' CRON_MAKE='$(CRON_MAKE)' bash utilities/install-crons.sh codex

install-app-health-cron:
	@APP_HEALTH_CRON_SCHEDULE='$(APP_HEALTH_CRON_SCHEDULE)' CRON_MAKE='$(CRON_MAKE)' bash utilities/install-crons.sh app-health

install-all-crons: docker-data-permissions
	@CRON_SCHEDULE='$(CRON_SCHEDULE)' CODEX_AUTH_CRON_SCHEDULE='$(CODEX_AUTH_CRON_SCHEDULE)' APP_HEALTH_CRON_SCHEDULE='$(APP_HEALTH_CRON_SCHEDULE)' CRON_MAKE='$(CRON_MAKE)' bash utilities/install-crons.sh all

# Install the daily PostgreSQL backup cron job only when no managed backup job exists.
ensure-pg-backup-cron:
	@current=$$(crontab -l 2>/dev/null || true); \
	for marker in $(PG_BACKUP_CRON_MARKERS); do \
		if printf '%s\n' "$$current" | grep -Fq "$$marker"; then \
			echo "✓ PostgreSQL backup cron job already installed"; \
			exit 0; \
		fi; \
	done; \
	$(MAKE) install-pg-backup-cron

# Build the project (skip tests)
build:
	./gradlew build -x test

# Run Spring Boot application (requires dev-up for local profile)
run:
	SPRING_PROFILES_ACTIVE=$(PROFILE) ./gradlew bootRun

# Run Spring Boot with local profile
run-local:
	SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# Run Spring Boot with prod profile
run-prod:
	SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun

# Run all tests
test:
	./gradlew $(if $(TEST_GRADLE_WORKERS),--max-workers=$(TEST_GRADLE_WORKERS),) $(if $(TEST_GRADLE_JVMARGS),-Dorg.gradle.jvmargs="$(TEST_GRADLE_JVMARGS)",) test $(TEST_GRADLE_ARGS)

# Configure repository-local git hooks.
install-git-hooks:
	git config core.hooksPath .githooks
	chmod +x .githooks/pre-commit
	@echo "✓ Git hooks installed"

# Install repository-provided Codex skills into the local Codex home.
install-codex-skills:
	@mkdir -p "$(CODEX_HOME)/skills"
	@count=0; \
	for skill in doc/codex-skills/SKIL_*; do \
		if [ -d "$$skill" ]; then \
			name=$$(basename "$$skill"); \
			rm -rf "$(CODEX_HOME)/skills/$$name"; \
			cp -R "$$skill" "$(CODEX_HOME)/skills/"; \
			count=$$((count + 1)); \
		fi; \
	done; \
	if [ "$$count" -eq 0 ]; then \
		echo "No Codex skills found in doc/codex-skills/SKIL_*"; \
		exit 1; \
	fi
	@echo "✓ Codex skills installed to $(CODEX_HOME)/skills"

# Install the same repository-provided skills into Claude Code's skills directory.
# Claude Code names skills by directory, so the SKIL_ prefix is dropped.
install-claude-skills:
	@mkdir -p "$(CLAUDE_CONFIG_DIR)/skills"
	@count=0; \
	for skill in doc/codex-skills/SKIL_*; do \
		if [ -d "$$skill" ]; then \
			name=$$(basename "$$skill" | sed 's/^SKIL_//'); \
			rm -rf "$(CLAUDE_CONFIG_DIR)/skills/$$name"; \
			cp -R "$$skill" "$(CLAUDE_CONFIG_DIR)/skills/$$name"; \
			count=$$((count + 1)); \
		fi; \
	done; \
	if [ "$$count" -eq 0 ]; then \
		echo "No skills found in doc/codex-skills/SKIL_*"; \
		exit 1; \
	fi
	@echo "✓ Claude skills installed to $(CLAUDE_CONFIG_DIR)/skills"

# Show sample prompts for repository-provided Codex skills.
codex-skill-prompts:
	@printf "Codex skill sample prompts\n\n"
	@count=0; \
	missing=0; \
	for skill in doc/codex-skills/SKIL_*; do \
		if [ -d "$$skill" ]; then \
			name=$$(basename "$$skill" | sed 's/^SKIL_//'); \
			prompt="$$skill/prompt.txt"; \
			printf '\033[1;94m%s\033[0m\n' "$$name"; \
			if [ -f "$$prompt" ]; then \
				sed 's/^/  /' "$$prompt"; \
			else \
				echo "  Missing $$prompt"; \
				missing=$$((missing + 1)); \
			fi; \
			printf '\n'; \
			count=$$((count + 1)); \
		fi; \
	done; \
	if [ "$$count" -eq 0 ]; then \
		echo "No Codex skills found in doc/codex-skills/SKIL_*"; \
		exit 1; \
	fi; \
	if [ "$$missing" -gt 0 ]; then \
		exit 1; \
	fi

# Show slash commands and sample prompts for the skills installed by install-claude-skills.
claude-skill-prompts:
	@printf "Claude skill sample prompts\n\n"
	@count=0; \
	missing=0; \
	for skill in doc/codex-skills/SKIL_*; do \
		if [ -d "$$skill" ]; then \
			name=$$(basename "$$skill" | sed 's/^SKIL_//'); \
			prompt="$$skill/prompt.txt"; \
			printf '\033[1;94m/%s\033[0m\n' "$$name"; \
			if [ -f "$$prompt" ]; then \
				sed 's/^/  /' "$$prompt"; \
			else \
				echo "  Missing $$prompt"; \
				missing=$$((missing + 1)); \
			fi; \
			printf '\n'; \
			count=$$((count + 1)); \
		fi; \
	done; \
	if [ "$$count" -eq 0 ]; then \
		echo "No skills found in doc/codex-skills/SKIL_*"; \
		exit 1; \
	fi; \
	if [ "$$missing" -gt 0 ]; then \
		exit 1; \
	fi

# Clean build artifacts
clean:
	./gradlew clean

# Auto-bump patch for semantic snapshot versions, e.g. 0.0.2-SNAPSHOT -> 0.0.3-SNAPSHOT
bump-patch:
	@current=$$(perl -ne 'print $$1 if /^version\s*=\s*"([^"]+)"/' build.gradle.kts); \
	if echo "$$current" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$$'; then \
		major=$$(echo "$$current" | cut -d. -f1); \
		minor=$$(echo "$$current" | cut -d. -f2); \
		patch=$$(echo "$$current" | sed -E 's/^[0-9]+\.[0-9]+\.([0-9]+)-SNAPSHOT$$/\1/'); \
		next_patch=$$((patch + 1)); \
		next="$$major.$$minor.$$next_patch-SNAPSHOT"; \
		perl -i -pe "s/^version\s*=\s*\"[^\"]+\"/version = \"$$next\"/" build.gradle.kts; \
		echo "✓ Version set to $$next"; \
	else \
		echo "Current version '$$current' is not x.y.z-SNAPSHOT; cannot auto-bump patch"; \
		exit 1; \
	fi

# Auto-bump minor for semantic snapshot versions, e.g. 0.0.2-SNAPSHOT -> 0.1.0-SNAPSHOT
bump-minor:
	@current=$$(perl -ne 'print $$1 if /^version\s*=\s*"([^"]+)"/' build.gradle.kts); \
	if echo "$$current" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$$'; then \
		major=$$(echo "$$current" | cut -d. -f1); \
		minor=$$(echo "$$current" | cut -d. -f2); \
		next_minor=$$((minor + 1)); \
		next="$$major.$$next_minor.0-SNAPSHOT"; \
		perl -i -pe "s/^version\s*=\s*\"[^\"]+\"/version = \"$$next\"/" build.gradle.kts; \
		echo "✓ Version set to $$next"; \
	else \
		echo "Current version '$$current' is not x.y.z-SNAPSHOT; cannot auto-bump minor"; \
		exit 1; \
	fi

# Stage all changes, ask Codex for a commit message, commit, and optionally push.
codex-commit:
	utilities/codex-commit.sh

# Same flow as codex-commit, with Claude Code writing the message and resolving pull conflicts.
claude-commit:
	utilities/claude-commit.sh

# Follow docker-compose logs
docker-logs:
	docker compose -f compose.yaml logs -f

# Open bash inside the running Spring Boot container
docker-spring-shell:
	docker compose -f compose.yaml exec springboot bash

# Pull latest code, rebuild, restart, and follow logs
docker-upgrade: docker-upgrade-log-owner docker-data-permissions
	git pull --ff-only
	docker-data/blue-green-upgrade.sh
	docker system prune -f
	docker compose -f compose.yaml logs -f

# Pull latest code, rebuild from scratch, restart, and follow logs. Use only when cache corruption is suspected.
docker-upgrade-no-cache: docker-upgrade-log-owner docker-data-permissions
	git pull --ff-only
	NO_CACHE=true docker-data/blue-green-upgrade.sh
	docker compose -f compose.yaml logs -f

# Sync server and worker environment files, then verify the resulting .env files byte-for-byte.
sync-env-files:
	@set -e; \
	blue=$$(printf '\033[1;94m'); \
	green=$$(printf '\033[1;92m'); \
	red=$$(printf '\033[1;91m'); \
	reset=$$(printf '\033[0m'); \
	remote_env=$$(mktemp); \
	trap 'rm -f "$$remote_env"' EXIT; \
	rsync -a -v .env.server $(ENV_SYNC_HOST):$(ENV_SYNC_DIR)/; \
	ssh $(ENV_SYNC_HOST) "cd $(ENV_SYNC_DIR); yes | cp .env.server .env"; \
	cp .env.worker .env; \
	echo ""; \
	printf "%sVerifying environment files ...%s\n" "$$blue" "$$reset"; \
	ssh $(ENV_SYNC_HOST) "cat $(ENV_SYNC_DIR)/.env" > "$$remote_env"; \
	failed=0; \
	if cmp -s .env.server "$$remote_env"; then \
		printf "%s✓ Server .env matches local .env.server%s\n" "$$green" "$$reset"; \
	else \
		printf "%s✗ Server .env differs from local .env.server%s\n" "$$red" "$$reset"; \
		failed=1; \
	fi; \
	if cmp -s .env.worker .env; then \
		printf "%s✓ Local .env matches .env.worker%s\n" "$$green" "$$reset"; \
	else \
		printf "%s✗ Local .env differs from .env.worker%s\n" "$$red" "$$reset"; \
		failed=1; \
	fi; \
	if [ "$$failed" -eq 0 ]; then \
		printf "%s✓ Environment files are in sync%s\n" "$$green" "$$reset"; \
	else \
		printf "%sEnvironment file verification failed%s\n" "$$red" "$$reset"; \
		exit 1; \
	fi

# Open PostgreSQL shell inside docker container (requires dev-up)
docker-pg-shell:
	docker compose -f compose.yaml exec postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)
