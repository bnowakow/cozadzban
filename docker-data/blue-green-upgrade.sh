#!/usr/bin/env sh
set -eu

compose_file="${COMPOSE_FILE:-compose.yaml}"
upstream_file="${UPSTREAM_FILE:-docker-data/nginx/upstream.conf}"
health_timeout_seconds="${HEALTH_TIMEOUT_SECONDS:-180}"
health_sleep_seconds="${HEALTH_SLEEP_SECONDS:-1}"
drain_seconds="${DRAIN_SECONDS:-3}"
no_cache="${NO_CACHE:-false}"
# local: build from this checkout. dockerhub: pull the image Jenkins pushed for this commit.
image_source="${IMAGE_SOURCE:-local}"
dockerhub_repository="${DOCKERHUB_REPOSITORY:-bnowakow/cozadzban}"
local_image="${LOCAL_IMAGE:-cozadzban:local}"
dockerhub_wait_seconds="${DOCKERHUB_WAIT_SECONDS:-1200}"
dockerhub_poll_seconds="${DOCKERHUB_POLL_SECONDS:-30}"
# Pulled images kept besides the deployed one, for rollbacks.
dockerhub_keep_images="${DOCKERHUB_KEEP_IMAGES:-3}"
health_lock_file="${APP_HEALTH_LOCK_FILE:-logs/app-health.lock}"

mkdir -p "$(dirname "$health_lock_file")"
exec 9>"$health_lock_file"
flock -x 9

mkdir -p "$(dirname "$upstream_file")"
if [ ! -f "$upstream_file" ]; then
    printf 'server springboot:8080 max_fails=3 fail_timeout=10s;\n' > "$upstream_file"
fi

write_upstream() {
    tmp_file="$upstream_file.tmp"
    : > "$tmp_file"
    for upstream in "$@"; do
        printf '%s\n' "$upstream" >> "$tmp_file"
    done
    cat "$tmp_file" > "$upstream_file"
    rm -f "$tmp_file"
}

reload_nginx() {
    docker compose -f "$compose_file" exec -T reverse-proxy nginx -t
    docker compose -f "$compose_file" exec -T reverse-proxy nginx -s reload
}

is_healthy() {
    docker compose -f "$compose_file" exec -T reverse-proxy \
        wget -qO- "http://$1:8080/actuator/health/readiness" | grep -q '"status":"UP"'
}

if grep -q 'springboot-green' "$upstream_file"; then
    old_service="springboot-green"
    new_service="springboot"
else
    old_service="springboot"
    new_service="springboot-green"
fi

echo "Active Spring service: $old_service"
echo "Starting replacement Spring service: $new_service"

build_locally() {
    if [ "$no_cache" = "true" ]; then
        DOCKER_BUILDKIT=1 docker compose -f "$compose_file" build --pull --no-cache springboot
    else
        DOCKER_BUILDKIT=1 docker compose -f "$compose_file" build --pull springboot
    fi
}

# The tag Jenkins pushes for a commit on main: <version without -SNAPSHOT>-<commit8>.
dockerhub_tag_for_head() {
    version=$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts | head -n 1)
    version=${version%-SNAPSHOT}
    commit=$(git rev-parse --short=8 HEAD)
    if [ -z "$version" ] || [ -z "$commit" ]; then
        echo "Could not read the version from build.gradle.kts or the git commit" >&2
        exit 1
    fi
    printf '%s-%s\n' "$version" "$commit"
}

dockerhub_image_exists() {
    docker manifest inspect "$1" >/dev/null 2>&1
}

# Asks on the terminal what to do while the image for this commit isn't on Docker Hub yet.
# Prints "wait" or "local"; anything else aborts without touching the running app.
# Uses a dialog/whiptail menu when available, otherwise a plain text prompt.
ask_missing_image() {
    # A subshell, because a failed redirection on a special builtin like : exits a POSIX shell.
    if ! (exec </dev/tty) 2>/dev/null; then
        echo "abort"
        return
    fi
    wait_text="Wait for it (check every ${dockerhub_poll_seconds}s for up to $(( dockerhub_wait_seconds / 60 )) minutes)"
    build_text="Build the image locally from this checkout instead"
    menu_bin=""
    if [ "${TERM:-dumb}" != "dumb" ]; then
        menu_bin=$(command -v dialog || command -v whiptail || true)
    fi
    if [ -n "$menu_bin" ]; then
        # The menu draws on the terminal and writes the chosen tag to fd 3, which is captured here.
        answer=$("$menu_bin" --title "Docker Hub image not ready" --output-fd 3 \
            --cancel-button "Abort" \
            --menu "$1 is not on Docker Hub yet. Jenkins builds it after the tests pass (about 10 minutes).\n\nAbort leaves the running app unchanged." \
            15 76 2 \
            w "$wait_text" \
            b "$build_text" \
            3>&1 1>/dev/tty 2>/dev/tty </dev/tty) || answer=""
        printf '\n' >/dev/tty
    else
        {
            echo ""
            echo "$1 is not on Docker Hub yet. Jenkins builds it after the tests pass (about 10 minutes)."
            echo "  [w] $wait_text"
            echo "  [b] $build_text"
            echo "  anything else: abort, nothing is changed"
            printf 'Choice [w/b]: '
        } >/dev/tty
        read -r answer </dev/tty || answer=""
    fi
    case "$answer" in
        w|W) echo "wait" ;;
        b|B) echo "local" ;;
        *) echo "abort" ;;
    esac
}

use_dockerhub_image() {
    tag="${IMAGE_TAG:-$(dockerhub_tag_for_head)}"
    image="$dockerhub_repository:$tag"
    echo "Deploying Docker Hub image $image"
    if ! dockerhub_image_exists "$image"; then
        if [ -n "${IMAGE_TAG:-}" ]; then
            echo "$image does not exist on Docker Hub" >&2
            exit 1
        fi
        case "$(ask_missing_image "$image")" in
            wait)
                deadline=$(( $(date +%s) + dockerhub_wait_seconds ))
                until dockerhub_image_exists "$image"; do
                    if [ "$(date +%s)" -ge "$deadline" ]; then
                        echo "$image did not appear on Docker Hub within $(( dockerhub_wait_seconds / 60 )) minutes; nothing was changed" >&2
                        exit 1
                    fi
                    echo "Waiting for $image ..."
                    sleep "$dockerhub_poll_seconds"
                done
                ;;
            local)
                echo "Building the image locally instead"
                build_locally
                return
                ;;
            *)
                echo "Aborted; $image is not on Docker Hub and nothing was changed" >&2
                exit 1
                ;;
        esac
    fi
    docker pull "$image"
    # Compose, the blue/green services and the ops containers all run cozadzban:local.
    docker tag "$image" "$local_image"
    echo "Tagged $image as $local_image"
    # Keep the newest pulled images for rollbacks (IMAGE_TAG=...), remove older ones.
    docker image ls "$dockerhub_repository" --format '{{.Repository}}:{{.Tag}}' \
        | grep -v ':<none>$' | grep -vx "$image" \
        | tail -n "+$(( dockerhub_keep_images + 1 ))" \
        | while read -r old_image; do docker image rm "$old_image" >/dev/null 2>&1 || true; done
}

case "$image_source" in
    local) build_locally ;;
    dockerhub) use_dockerhub_image ;;
    *)
        echo "IMAGE_SOURCE must be local or dockerhub, not $image_source" >&2
        exit 1
        ;;
esac

# A Docker Hub deploy of the image the active service already runs changes nothing, so stop here.
# FORCE_UPGRADE=true recreates it anyway, e.g. to apply .env or compose.yaml changes.
if [ "$image_source" = "dockerhub" ] && [ "${FORCE_UPGRADE:-false}" != "true" ]; then
    running_container=$(docker compose -f "$compose_file" ps -q --status running "$old_service" 2>/dev/null || true)
    if [ -n "$running_container" ] \
        && [ "$(docker inspect --format '{{.Image}}' "$running_container")" = "$(docker image inspect --format '{{.Id}}' "$local_image")" ]; then
        echo "$old_service already runs ${image:-this image}; nothing to upgrade (FORCE_UPGRADE=true redeploys anyway)"
        exit 0
    fi
fi

docker compose -f "$compose_file" up -d --no-recreate postgres zipkin "$old_service" reverse-proxy
docker compose -f "$compose_file" up -d --no-deps --force-recreate "$new_service"

echo "Waiting for $new_service to report healthy..."
deadline=$(( $(date +%s) + health_timeout_seconds ))
healthy=false
while [ "$(date +%s)" -lt "$deadline" ]; do
    if is_healthy "$new_service"; then
        echo "$new_service is healthy"
        healthy=true
        break
    fi
    sleep "$health_sleep_seconds"
done

if [ "$healthy" != "true" ]; then
    echo "Timed out waiting for $new_service health endpoint" >&2
    docker compose -f "$compose_file" logs --tail=100 "$new_service" >&2
    exit 1
fi

write_upstream \
    "server $new_service:8080 max_fails=1 fail_timeout=1s;" \
    "server $old_service:8080 backup max_fails=1 fail_timeout=1s;"
reload_nginx
echo "Reverse proxy now prefers $new_service and keeps $old_service as a temporary backup"

if [ "$drain_seconds" -gt 0 ]; then
    echo "Waiting ${drain_seconds}s before stopping $old_service..."
    sleep "$drain_seconds"
fi

docker compose -f "$compose_file" stop "$old_service" >/dev/null 2>&1 || true

write_upstream "server $new_service:8080 max_fails=1 fail_timeout=5s;"
reload_nginx
echo "Reverse proxy now points only to $new_service"

docker compose -f "$compose_file" ps
echo "Old Spring service stopped: $old_service"
