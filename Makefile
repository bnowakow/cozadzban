
.PHONY: help docker-up docker-down docker-pg-nuke build run run-local run-prod test clean docker-logs docker-pg-shell docker-upgrade

-include .env

# Default target
help:
	@echo "CoZaZjeb — Makefile targets"
	@echo ""
	@echo "  PostgreSQL port from .env: $(POSTGRES_PORT)"
	@echo ""
	@echo "  docker-up       Start local infrastructure from compose.yaml"
	@echo "  docker-down     Stop and remove local infrastructure containers"
	@echo "  docker-pg-nuke  Recreate PostgreSQL container and reset docker-data/postgres"
	@echo "  build           Build the project (skip tests)"
	@echo "  run             Run Spring Boot with SPRING_PROFILES_ACTIVE from .env"
	@echo "  run-local       Run Spring Boot with local profile"
	@echo "  run-prod        Run Spring Boot with prod profile"
	@echo "  test            Run all tests"
	@echo "  clean           Clean Gradle build artifacts"
	@echo "  docker-logs     Show compose logs (follow mode)"
	@echo "  docker-pg-shell Open PostgreSQL shell inside docker container"
	@echo "  docker-upgrade  Pull latest code, rebuild image, restart containers, follow logs"
	@echo ""

# Active Spring profile used by the generic run target.
PROFILE ?= $(SPRING_PROFILES_ACTIVE)
PROFILE ?= local
POSTGRES_PORT ?= 5432

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
	rm -rf ./docker-data/postgres
	$(MAKE) docker-up

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

# Clean build artifacts
clean:
	./gradlew clean

# Follow docker-compose logs
docker-logs:
	docker compose -f compose.yaml logs -f

# Pull latest code, rebuild, restart, and follow logs
docker-upgrade:
	git pull
	docker compose -f compose.yaml build
	$(MAKE) docker-down
	$(MAKE) docker-up
	$(MAKE) docker-logs

# Open PostgreSQL shell inside docker container (requires dev-up)
docker-pg-shell:
	docker compose -f compose.yaml exec postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)

