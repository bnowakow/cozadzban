
.PHONY: help dev-up dev-down build run run-local run-prod test clean logs pg-shell-docker shell

-include .env

# Default target
help:
	@echo "CoZaZjeb — Makefile targets"
	@echo ""
	@echo "  PostgreSQL port from .env: $(POSTGRES_PORT)"
	@echo ""
	@echo "  dev-up          Start local infrastructure from compose.yaml"
	@echo "  dev-down        Stop and remove local infrastructure containers"
	@echo "  build           Build the project (skip tests)"
	@echo "  run             Run Spring Boot with SPRING_PROFILES_ACTIVE from .env"
	@echo "  run-local       Run Spring Boot with local profile"
	@echo "  run-prod        Run Spring Boot with prod profile"
	@echo "  test            Run all tests"
	@echo "  clean           Clean Gradle build artifacts"
	@echo "  logs            Show compose logs (follow mode)"
	@echo "  pg-shell-docker Open PostgreSQL shell inside docker container"
	@echo "  shell           Alias for pg-shell-docker"
	@echo ""

# Active Spring profile used by the generic run target.
PROFILE ?= $(SPRING_PROFILES_ACTIVE)
PROFILE ?= local
POSTGRES_PORT ?= 5432

# Start local development environment from compose.yaml
dev-up:
	docker compose -f compose.yaml up -d
	@echo ""
	@echo "✓ Services started:"
	@echo "  - PostgreSQL:     localhost:$(POSTGRES_PORT)"
	@echo ""

# Stop local development environment
dev-down:
	docker compose -f compose.yaml down

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
logs:
	docker compose -f compose.yaml logs -f

# Open PostgreSQL shell inside docker container (requires dev-up)
pg-shell-docker:
	docker compose -f compose.yaml exec postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)

