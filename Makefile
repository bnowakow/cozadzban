
.PHONY: help docker-up docker-down docker-pg-nuke docker-pg-backup build run run-local run-prod test clean docker-logs docker-spring-shell docker-pg-shell docker-upgrade bump-version bump-patch bump-minor install-git-hooks

-include .env

# Default target
help:
	@echo "CoZaZjeb — Makefile targets"
	@echo ""
	@echo "  PostgreSQL port from .env: $(POSTGRES_PORT)"
	@echo ""
	@echo "  Docker"
	@echo "    docker-up          Start local infrastructure from compose.yaml"
	@echo "    docker-down        Stop and remove local infrastructure containers"
	@echo "    docker-logs        Show compose logs (follow mode)"
	@echo "    docker-spring-shell Open bash inside the running Spring Boot container"
	@echo "    docker-upgrade     Pull latest code, rebuild image, restart containers, follow logs"
	@echo ""
	@echo "  PostgreSQL in Docker"
	@echo "    docker-pg-nuke     Recreate PostgreSQL container and reset docker-data/postgres"
	@echo "    docker-pg-backup   Dump PostgreSQL and zip it into docker-data/backup/postgres"
	@echo "    docker-pg-shell    Open PostgreSQL shell inside docker container"
	@echo ""
	@echo "  Application"
	@echo "    build              Build the project (skip tests)"
	@echo "    run                Run Spring Boot with SPRING_PROFILES_ACTIVE from .env"
	@echo "    run-local          Run Spring Boot with local profile"
	@echo "    run-prod           Run Spring Boot with prod profile"
	@echo "    test               Run all tests"
	@echo "    clean              Clean Gradle build artifacts"
	@echo ""
	@echo "  Versioning"
	@echo "    bump-version       Set project version in build.gradle.kts (use VERSION=x.y.z[-SNAPSHOT])"
	@echo "    bump-patch         Auto-increment patch for x.y.z-SNAPSHOT versions"
	@echo "    bump-minor         Auto-increment minor and reset patch for x.y.z-SNAPSHOT versions"
	@echo ""
	@echo "  Repository"
	@echo "    install-git-hooks  Configure repository git hooks"
	@echo ""

# Active Spring profile used by the generic run target.
PROFILE ?= $(SPRING_PROFILES_ACTIVE)
PROFILE ?= local
POSTGRES_PORT ?= 5432
LOCAL_UID ?= $(shell id -u)
LOCAL_GID ?= $(shell id -g)
APP_BUILD_COMMIT ?= $(shell git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)
export APP_BUILD_COMMIT

# Start local development environment from compose.yaml
docker-up:
	docker compose -f compose.yaml up -d
	@echo ""
	@echo "✓ Services started:"
	@echo "  - PostgreSQL:     localhost:$(POSTGRES_PORT)"
	@echo ""

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
docker-pg-backup:
	@mkdir -p ./docker-data/backup/postgres
	@timestamp=$$(date +"%Y-%m-%d_%H-%M-%S"); \
	base="cozazjeb-postgres-$$timestamp"; \
	sql_path="./docker-data/backup/postgres/$$base.sql"; \
	zip_path="./docker-data/backup/postgres/$$base.zip"; \
	echo "Dumping PostgreSQL database '$(POSTGRES_DB)' to $$sql_path ..."; \
	docker compose -f compose.yaml exec -T postgres pg_dump -U "$(POSTGRES_USER)" -d "$(POSTGRES_DB)" --clean --if-exists > "$$sql_path"; \
	zip -j "$$zip_path" "$$sql_path" >/dev/null; \
	rm "$$sql_path"; \
	echo "✓ Backup written to $$zip_path"

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

# Clean build artifacts
clean:
	./gradlew clean

# Set project version in build.gradle.kts, e.g. make bump-version VERSION=0.0.3-SNAPSHOT
bump-version:
	@if [ -z "$(VERSION)" ]; then \
		echo "Usage: make bump-version VERSION=x.y.z[-SNAPSHOT]"; \
		exit 1; \
	fi
	@perl -i -pe 's/^version\s*=\s*"[^"]+"/version = "$(VERSION)"/' build.gradle.kts
	@echo "✓ Version set to $(VERSION)"

# Auto-bump patch for semantic snapshot versions, e.g. 0.0.2-SNAPSHOT -> 0.0.3-SNAPSHOT
bump-patch:
	@current=$$(perl -ne 'print $$1 if /^version\s*=\s*"([^"]+)"/' build.gradle.kts); \
	if echo "$$current" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$$'; then \
		major=$$(echo "$$current" | cut -d. -f1); \
		minor=$$(echo "$$current" | cut -d. -f2); \
		patch=$$(echo "$$current" | sed -E 's/^[0-9]+\.[0-9]+\.([0-9]+)-SNAPSHOT$$/\1/'); \
		next_patch=$$((patch + 1)); \
		next="$$major.$$minor.$$next_patch-SNAPSHOT"; \
		$(MAKE) bump-version VERSION="$$next"; \
	else \
		echo "Current version '$$current' is not x.y.z-SNAPSHOT; use make bump-version VERSION=..."; \
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
		$(MAKE) bump-version VERSION="$$next"; \
	else \
		echo "Current version '$$current' is not x.y.z-SNAPSHOT; use make bump-version VERSION=..."; \
		exit 1; \
	fi

# Follow docker-compose logs
docker-logs:
	docker compose -f compose.yaml logs -f

# Open bash inside the running Spring Boot container
docker-spring-shell:
	docker compose -f compose.yaml exec springboot bash

# Pull latest code, rebuild, restart, and follow logs
docker-upgrade:
	git pull --ff-only
	docker compose -f compose.yaml build --pull --no-cache springboot
	docker compose -f compose.yaml down --remove-orphans
	docker compose -f compose.yaml up -d --force-recreate
	docker compose -f compose.yaml ps
	docker compose -f compose.yaml logs -f

# Open PostgreSQL shell inside docker container (requires dev-up)
docker-pg-shell:
	docker compose -f compose.yaml exec postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)
