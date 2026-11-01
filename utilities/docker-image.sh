#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 https://bnowakowski.pl
#
# Builds or pushes the Docker Hub image, as the Jenkinsfile "Docker image" stage
# does (`make docker-image-build` / `make docker-image-push`). Tags:
# <version>, <version>-<commit8> and latest, where <version> is the
# build.gradle.kts version without -SNAPSHOT; docker-data/blue-green-upgrade.sh
# computes the same <version>-<commit8> when deploying.
#
# Usage: utilities/docker-image.sh build|push
# The commit is GIT_COMMIT (set by Jenkins) or the checkout's HEAD. Pushing needs
# a prior `docker login`; this script never logs in. POSIX sh, because the
# Jenkins docker:cli container is Alpine without bash.
set -eu

cd "$(dirname "$0")/.."

IMAGE=${IMAGE:-bnowakow/cozadzban}
full_commit=${GIT_COMMIT:-$(git rev-parse HEAD)}
commit=$(printf '%.8s' "$full_commit")
version=$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)
version=${version%-SNAPSHOT}
[ -n "$version" ] || { echo "No version found in build.gradle.kts" >&2; exit 1; }

case "${1:-}" in
  build)
    echo "Building $IMAGE: $version, $version-$commit, latest"
    docker build \
      --build-arg APP_BUILD_COMMIT="$commit" \
      --label org.opencontainers.image.version="$version" \
      --label org.opencontainers.image.revision="$full_commit" \
      --label org.opencontainers.image.source=https://github.com/bnowakow/cozadzban \
      --tag "$IMAGE:$version" \
      --tag "$IMAGE:$version-$commit" \
      --tag "$IMAGE:latest" \
      .
    ;;
  push)
    # The fixed per-commit tag first, so moving tags never point at an unpushed build.
    docker push "$IMAGE:$version-$commit"
    docker push "$IMAGE:$version"
    docker push "$IMAGE:latest"
    ;;
  *)
    echo "Usage: $0 build|push" >&2
    exit 2
    ;;
esac
