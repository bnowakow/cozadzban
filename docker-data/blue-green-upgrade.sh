#!/usr/bin/env sh
set -eu

compose_file="${COMPOSE_FILE:-compose.yaml}"
upstream_file="${UPSTREAM_FILE:-docker-data/nginx/upstream.conf}"
health_timeout_seconds="${HEALTH_TIMEOUT_SECONDS:-180}"
health_sleep_seconds="${HEALTH_SLEEP_SECONDS:-5}"
drain_seconds="${DRAIN_SECONDS:-10}"
no_cache="${NO_CACHE:-false}"

mkdir -p "$(dirname "$upstream_file")"
if [ ! -f "$upstream_file" ]; then
    printf 'server springboot:8080 max_fails=3 fail_timeout=10s;\n' > "$upstream_file"
fi

if grep -q 'springboot-green' "$upstream_file"; then
    old_service="springboot-green"
    new_service="springboot"
else
    old_service="springboot"
    new_service="springboot-green"
fi

echo "Active Spring service: $old_service"
echo "Starting replacement Spring service: $new_service"

if [ "$no_cache" = "true" ]; then
    DOCKER_BUILDKIT=1 docker compose -f "$compose_file" build --pull --no-cache springboot
else
    DOCKER_BUILDKIT=1 docker compose -f "$compose_file" build --pull springboot
fi

docker compose -f "$compose_file" up -d postgres zipkin reverse-proxy
docker compose -f "$compose_file" up -d --no-deps --force-recreate "$new_service"

echo "Waiting for $new_service to report healthy..."
deadline=$(( $(date +%s) + health_timeout_seconds ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if docker compose -f "$compose_file" exec -T reverse-proxy \
        wget -qO- "http://$new_service:8080/actuator/health" | grep -q '"status":"UP"'; then
        echo "$new_service is healthy"
        break
    fi
    sleep "$health_sleep_seconds"
done

if ! docker compose -f "$compose_file" exec -T reverse-proxy \
    wget -qO- "http://$new_service:8080/actuator/health" | grep -q '"status":"UP"'; then
    echo "Timed out waiting for $new_service health endpoint" >&2
    docker compose -f "$compose_file" logs --tail=100 "$new_service" >&2
    exit 1
fi

tmp_file="$upstream_file.tmp"
printf 'server %s:8080 max_fails=3 fail_timeout=10s;\n' "$new_service" > "$tmp_file"
cat "$tmp_file" > "$upstream_file"
rm -f "$tmp_file"

docker compose -f "$compose_file" exec -T reverse-proxy nginx -s reload
echo "Reverse proxy now points to $new_service"

if [ "$drain_seconds" -gt 0 ]; then
    echo "Waiting ${drain_seconds}s before stopping $old_service..."
    sleep "$drain_seconds"
fi

docker compose -f "$compose_file" stop "$old_service" >/dev/null 2>&1 || true
docker compose -f "$compose_file" ps
echo "Old Spring service stopped: $old_service"
