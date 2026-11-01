#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 https://bnowakowski.pl
#
# `make ci-test`: runs the test suite the way CI does. Used by the Jenkinsfile
# "Test" stage and `make docker-test` (utilities/docker-test.sh), which both run
# it in eclipse-temurin:21-jdk-jammy next to a docker:dind daemon.
#
# Waits for the Docker daemon first: build.gradle.kts runs `docker info` and
# silently excludes the *IT tests and CozadzbanApplicationTests when it fails.
# Extra arguments are passed to Gradle.
set -eu

cd "$(dirname "$0")/.."
# The docker CLI copied in from docker:cli (see the Jenkinsfile pod).
export PATH="/opt/docker-cli:$PATH"

if [ "$(hostname)" = "ovh.bnowakowski.pl" ]; then
  echo "Refusing to run tests on the deployment server ovh.bnowakowski.pl; run this on a development machine." >&2
  exit 1
fi

i=0
until docker info >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "Docker daemon at ${DOCKER_HOST:-default socket} did not become ready in 120 s" >&2
    docker info >&2 || true
    exit 1
  fi
  sleep 2
done
docker info --format 'Docker {{.ServerVersion}} ready'

./gradlew --no-daemon test -PappBuildCommit="${APP_BUILD_COMMIT:-unknown}" "$@"
