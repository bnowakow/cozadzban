
.PHONY: help docker-up docker-down docker-data-permissions docker-pg-nuke docker-pg-backup install-pg-backup-cron build run run-local run-prod test clean docker-logs docker-spring-shell docker-pg-shell docker-upgrade docker-upgrade-no-cache bump-patch bump-minor install-git-hooks install-codex-skills

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
	@printf "\n"
	@printf "  \033[1;94m%s\033[0m\n" "PostgreSQL in Docker"
	@printf "    %-31s %s\n" "docker-pg-nuke" "Recreate PostgreSQL container and reset docker-data/postgres"
	@printf "    %-31s %s\n" "docker-pg-backup" "Dump PostgreSQL and zip it into docker-data/backup/postgres"
	@printf "    %-31s %s\n" "install-pg-backup-cron" "Install daily 02:00 PostgreSQL backup cron job"
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
	@printf "    %-31s %s\n" "install-git-hooks" "Configure repository git hooks"
	@printf "    %-31s %s\n" "install-codex-skills" "Install all project Codex skills into CODEX_HOME"
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
CRON_SCHEDULE ?= 0 2 * * *
CRON_MAKE ?= $(shell command -v make 2>/dev/null || echo make)
PG_BACKUP_CRON_MARKER ?= cozadzban-docker-pg-backup
export APP_BUILD_COMMIT

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
	@docker run --rm -v "$(PWD)/docker-data:/work" alpine:3.20 \
		sh -c "mkdir -p /work/postgres /work/data/favicons /work/backup/postgres /work/nginx && if [ ! -f /work/nginx/upstream.conf ]; then printf 'server springboot:8080 max_fails=3 fail_timeout=10s;\n' > /work/nginx/upstream.conf; fi && chown -R $(LOCAL_UID):$(LOCAL_GID) /work/backup /work/nginx && chmod 755 /work /work/postgres /work/nginx && chmod -R a+rwX /work/data && chmod -R u+rwX,go-rwx /work/backup && chmod 644 /work/nginx/upstream.conf"

# Stop local development environment
docker-down:
	docker compose -f compose.yaml down

# Recreate PostgreSQL container with a fresh data directory
docker-pg-nuke:
	$(MAKE) docker-down
	@echo "Resetting ./docker-data/postgres ..."
	@if rm -rf ./docker-data/postgres 2>/dev/null; then \
		echo "✓ Removed postgres data directory"; \
	else \
		echo "! Host cleanup failed (likely root-owned files), using containerized cleanup"; \
		docker run --rm -v "$(PWD)/docker-data:/work" alpine:3.20 \
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
	@job='$(CRON_SCHEDULE) cd $(PWD) && $(CRON_MAKE) docker-pg-backup >> $(PWD)/docker-data/backup/postgres/cron.log 2>&1 # $(PG_BACKUP_CRON_MARKER)'; \
	( crontab -l 2>/dev/null | grep -Fv '$(PG_BACKUP_CRON_MARKER)' ; echo "$$job" ) | crontab -
	@echo "✓ Installed daily PostgreSQL backup cron job at 02:00"

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
	./gradlew test

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

# Follow docker-compose logs
docker-logs:
	docker compose -f compose.yaml logs -f

# Open bash inside the running Spring Boot container
docker-spring-shell:
	docker compose -f compose.yaml exec springboot bash

# Pull latest code, rebuild, restart, and follow logs
docker-upgrade: docker-data-permissions
	git pull --ff-only
	docker-data/blue-green-upgrade.sh
	docker compose -f compose.yaml logs -f

# Pull latest code, rebuild from scratch, restart, and follow logs. Use only when cache corruption is suspected.
docker-upgrade-no-cache: docker-data-permissions
	git pull --ff-only
	NO_CACHE=true docker-data/blue-green-upgrade.sh
	docker compose -f compose.yaml logs -f

# Open PostgreSQL shell inside docker container (requires dev-up)
docker-pg-shell:
	docker compose -f compose.yaml exec postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)
