#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 https://bnowakowski.pl
#
# `make docker-test`: runs the test suite in containers that mirror the Jenkins
# build pod (see the Jenkinsfile): eclipse-temurin:21-jdk-jammy for Gradle, a
# privileged docker:27.5.1-dind daemon on 127.0.0.1:2375 for Testcontainers, and
# the docker CLI copied in from docker:27.5.1-cli. Both containers share one
# network namespace, like containers in a pod. The tests run `make ci-test`,
# like the Jenkins "Test" stage.
#
# The working tree (tracked and untracked, non-ignored files, including
# uncommitted changes) is copied into the container, so the host's build/ and
# .gradle/ are not used or touched. Test results and reports are copied back to
# build/docker-test/.
#
# Unlike Jenkins, the Gradle cache and the dind image store are kept in named
# volumes between runs. DOCKER_TEST_CLEAN=true removes them first, for a cold
# start like Jenkins. Extra arguments are passed to Gradle.
set -euo pipefail

cd "$(dirname "$0")/.."

JDK_IMAGE=eclipse-temurin:21-jdk-jammy
DIND_IMAGE=docker:27.5.1-dind
CLI_IMAGE=docker:27.5.1-cli
PREFIX=cozadzban-docker-test
JDK_CONTAINER=$PREFIX-jdk
DIND_CONTAINER=$PREFIX-dind
CLI_VOLUME=$PREFIX-cli
GRADLE_VOLUME=$PREFIX-gradle
DOCKER_VOLUME=$PREFIX-docker
RESULTS_DIR=build/docker-test
# Same limits as the Jenkins pod.
DOCKER_TEST_CPUS=${DOCKER_TEST_CPUS:-4}
DOCKER_TEST_MEMORY=${DOCKER_TEST_MEMORY:-6g}
APP_BUILD_COMMIT=${APP_BUILD_COMMIT:-$(git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)}

if [ "$(hostname)" = "ovh.bnowakowski.pl" ]; then
  echo "Refusing to run tests on the deployment server ovh.bnowakowski.pl; run this on a development machine." >&2
  exit 1
fi
docker info >/dev/null 2>&1 || { echo "Docker is not running on this machine" >&2; exit 1; }

if docker container inspect "$JDK_CONTAINER" "$DIND_CONTAINER" >/dev/null 2>&1; then
  echo "Another docker-test run is in progress (container $JDK_CONTAINER or $DIND_CONTAINER exists)." >&2
  echo "If it is left over, remove it with: docker rm -f $JDK_CONTAINER $DIND_CONTAINER" >&2
  exit 1
fi

cleanup() {
  docker rm -f "$JDK_CONTAINER" "$DIND_CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$CLI_VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "${DOCKER_TEST_CLEAN:-false}" = "true" ]; then
  echo "Removing cached volumes $GRADLE_VOLUME and $DOCKER_VOLUME"
  docker volume rm "$GRADLE_VOLUME" "$DOCKER_VOLUME" >/dev/null 2>&1 || true
fi

# Init container of the Jenkins pod: copy the docker CLI for the JDK container.
docker run --rm -v "$CLI_VOLUME:/opt/docker-cli" "$CLI_IMAGE" \
  sh -c "cp /usr/local/bin/docker /opt/docker-cli/docker"

docker run -d --name "$DIND_CONTAINER" --privileged \
  --memory "$DOCKER_TEST_MEMORY" --cpus "$DOCKER_TEST_CPUS" \
  -e DOCKER_TLS_CERTDIR= \
  -v "$DOCKER_VOLUME:/var/lib/docker" \
  "$DIND_IMAGE" \
  dockerd --host=tcp://127.0.0.1:2375 --host=unix:///var/run/docker.sock --tls=false >/dev/null

docker run -d --name "$JDK_CONTAINER" --network "container:$DIND_CONTAINER" \
  --memory "$DOCKER_TEST_MEMORY" --cpus "$DOCKER_TEST_CPUS" \
  -e DOCKER_HOST=tcp://127.0.0.1:2375 \
  -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e TZ=Europe/Warsaw \
  -e APP_BUILD_COMMIT="$APP_BUILD_COMMIT" \
  -e GRADLE_USER_HOME=/gradle-home \
  -v "$CLI_VOLUME:/opt/docker-cli:ro" \
  -v "$GRADLE_VOLUME:/gradle-home" \
  -w /workspace \
  "$JDK_IMAGE" sleep infinity >/dev/null

echo "Copying the working tree into $JDK_CONTAINER"
docker exec "$JDK_CONTAINER" mkdir -p /workspace
# Tracked files deleted in the working tree are still listed by git ls-files; skip them.
git ls-files -z --cached --others --exclude-standard \
  | while IFS= read -r -d '' file; do
      if [ -e "$file" ] || [ -L "$file" ]; then printf '%s\0' "$file"; fi
    done \
  | COPYFILE_DISABLE=1 tar --null --no-xattrs -T - -cf - \
  | docker exec -i "$JDK_CONTAINER" tar --warning=no-unknown-keyword -xf - -C /workspace

# Same commands as the Jenkins "Test" stage.
docker exec "$JDK_CONTAINER" sh -c 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends make >/dev/null'
status=0
docker exec -e TEST_GRADLE_ARGS="$*" "$JDK_CONTAINER" make ci-test || status=$?

rm -rf "$RESULTS_DIR"
mkdir -p "$RESULTS_DIR"
docker cp "$JDK_CONTAINER:/workspace/build/test-results/test" "$RESULTS_DIR/test-results" >/dev/null 2>&1 || true
docker cp "$JDK_CONTAINER:/workspace/build/reports/tests/test" "$RESULTS_DIR/reports" >/dev/null 2>&1 || true
if [ -d "$RESULTS_DIR/reports" ]; then
  echo "Test report: $RESULTS_DIR/reports/index.html"
fi

exit "$status"
