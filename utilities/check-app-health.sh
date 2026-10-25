#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

compose_file=${APP_HEALTH_COMPOSE_FILE:-compose.yaml}
upstream_file=${APP_HEALTH_UPSTREAM_FILE:-docker-data/nginx/upstream.conf}
lock_file=${APP_HEALTH_LOCK_FILE:-logs/app-health.lock}
state_file=${APP_HEALTH_STATE_FILE:-logs/app-health.state}
health_url=${APP_HEALTH_URL:-http://127.0.0.1:${APP_PORT:-8086}/actuator/health/readiness}
failure_threshold=${APP_HEALTH_FAILURE_THRESHOLD:-3}
cooldown_seconds=${APP_HEALTH_COOLDOWN_SECONDS:-900}
startup_grace_seconds=${APP_HEALTH_STARTUP_GRACE_SECONDS:-180}

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
save_state() {
  printf '%s %s %s %s\n' "$failures" "$last_restart" "$active_service" "$restart_pending" >"$state_file.tmp.$$"
  mv -f -- "$state_file.tmp.$$" "$state_file"
}
is_up() { grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$1"; }

if [[ ! "$failure_threshold" =~ ^[1-9][0-9]*$ || ! "$cooldown_seconds" =~ ^[0-9]+$ || ! "$startup_grace_seconds" =~ ^[0-9]+$ ]]; then
  log "Invalid app health failure threshold, cooldown, or startup grace" >&2
  exit 2
fi

mkdir -p -- "$(dirname -- "$lock_file")" "$(dirname -- "$state_file")"
exec 9>"$lock_file"
if ! flock -n 9; then
  log "App health check skipped: deployment or another check is running"
  exit 0
fi

if [[ ! -f "$upstream_file" ]]; then
  log "App health check failed: active upstream file is missing" >&2
  exit 1
fi
mapfile -t upstreams < <(awk '$1 == "server" { print $2 }' "$upstream_file")
if (( ${#upstreams[@]} != 1 )); then
  log "App health check skipped: upstream switch is in progress or no active service is configured"
  exit 0
fi
case "${upstreams[0]}" in
  springboot:8080) active_service=springboot ;;
  springboot-green:8080) active_service=springboot-green ;;
  *) log "App health check failed: unknown active upstream ${upstreams[0]}" >&2; exit 1 ;;
esac

failures=0
last_restart=0
previous_service=
restart_pending=0
if [[ -f "$state_file" ]]; then
  read -r failures last_restart previous_service restart_pending <"$state_file" || true
fi
if [[ ! "$failures" =~ ^[0-9]+$ || ! "$last_restart" =~ ^[0-9]+$ || "$previous_service" != "$active_service" ]]; then
  failures=0
  last_restart=0
  restart_pending=0
fi
[[ "$restart_pending" =~ ^[01]$ ]] || restart_pending=0

if response=$(curl --silent --show-error --fail --connect-timeout 2 --max-time 5 "$health_url" 2>/dev/null) && is_up "$response"; then
  if (( failures > 0 || restart_pending == 1 )); then
    log "App health recovered: $active_service"
  elif [[ ${APP_HEALTH_QUIET:-0} != 1 ]]; then
    log "App health OK: $active_service"
  fi
  failures=0
  restart_pending=0
  save_state
  exit 0
fi

now=$(date +%s)
if (( last_restart > 0 && now >= last_restart && now - last_restart < startup_grace_seconds )); then
  failures=0
  save_state
  log "App readiness pending after restart: $active_service ($((startup_grace_seconds - now + last_restart))s grace remaining)"
  exit 0
fi

failures=$((failures + 1))
save_state
log "App health failed: $active_service ($failures/$failure_threshold consecutive checks)"
if (( failures < failure_threshold )); then
  exit 1
fi

if (( now - last_restart < cooldown_seconds )); then
  log "App restart skipped: cooldown remains after the last restart" >&2
  exit 1
fi

# Only restart the active app if Docker and the proxy are available. A proxy-only
# failure needs investigation, and restarting a healthy app would make it worse.
if ! container_id=$(timeout 10s docker compose -f "$compose_file" ps -q "$active_service") || [[ -z "$container_id" ]]; then
  log "App restart skipped: active container is not running or Docker is unavailable" >&2
  exit 1
fi
if ! proxy_id=$(timeout 10s docker compose -f "$compose_file" ps -q reverse-proxy) || [[ -z "$proxy_id" ]]; then
  log "App restart skipped: reverse proxy is not running or Docker is unavailable" >&2
  exit 1
fi
if direct_response=$(timeout 8s docker compose -f "$compose_file" exec -T reverse-proxy \
    wget -qO- -T 5 "http://$active_service:8080/actuator/health/readiness" 2>/dev/null) && is_up "$direct_response"; then
  log "App restart skipped: direct app probe succeeded; investigate the proxy" >&2
  exit 1
fi
if live_response=$(timeout 8s docker compose -f "$compose_file" exec -T reverse-proxy \
    wget -qO- -T 5 "http://$active_service:8080/actuator/health/liveness" 2>/dev/null) && is_up "$live_response"; then
  log "App restart skipped: app is live but not ready; investigate its dependencies" >&2
  exit 1
fi

log "Restarting unresponsive app service: $active_service"
last_restart=$(date +%s)
restart_pending=1
failures=0
save_state
if timeout 90s docker compose -f "$compose_file" restart --no-deps -t 20 "$active_service"; then
  last_restart=$(date +%s)
  save_state
  log "App service restarted: $active_service; readiness has ${startup_grace_seconds}s to recover"
else
  log "App service restart command failed: $active_service; grace and cooldown still apply" >&2
  exit 1
fi
