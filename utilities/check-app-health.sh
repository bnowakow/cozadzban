#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

compose_file=${APP_HEALTH_COMPOSE_FILE:-compose.yaml}
upstream_file=${APP_HEALTH_UPSTREAM_FILE:-docker-data/nginx/upstream.conf}
lock_file=${APP_HEALTH_LOCK_FILE:-logs/app-health.lock}
state_file=${APP_HEALTH_STATE_FILE:-logs/app-health.state}
restart_history_file=${APP_HEALTH_RESTART_HISTORY_FILE:-logs/app-health-restarts.state}
diagnostic_dir=${APP_HEALTH_DIAGNOSTIC_DIR:-logs/app-health-diagnostics}
health_url=${APP_HEALTH_URL:-http://127.0.0.1:${APP_PORT:-8086}/actuator/health/readiness}
failure_threshold=${APP_HEALTH_FAILURE_THRESHOLD:-3}
cooldown_seconds=${APP_HEALTH_COOLDOWN_SECONDS:-900}
startup_grace_seconds=${APP_HEALTH_STARTUP_GRACE_SECONDS:-180}
max_restarts_per_hour=${APP_HEALTH_MAX_RESTARTS_PER_HOUR:-2}
alert_email=${APP_HEALTH_ALERT_EMAIL:-${MAILTO:-}}

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
save_state() {
  printf '%s %s %s %s %s\n' "$failures" "$last_restart" "$active_service" "$restart_pending" "$alert_stage" >"$state_file.tmp.$$"
  mv -f -- "$state_file.tmp.$$" "$state_file"
}
is_up() { grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$1"; }
notify_operator() {
  local subject=$1 message=$2
  log "ALERT: $message"
  logger -t cozadzban-app-health -- "$message" || true
  if [[ -n "$alert_email" ]]; then
    if ! printf '%s\nHost: %s\nLog: %s\n' "$message" "$(hostname)" "$repo_root/logs/app-health-check.log" |
      timeout 15s mail -s "$subject" -- "$alert_email"; then
      log "Alert email could not be queued" >&2
    fi
  fi
}
load_restart_history() {
  restart_times=()
  if [[ -f "$restart_history_file" ]]; then
    while IFS= read -r restart_time; do
      if [[ "$restart_time" =~ ^[0-9]+$ ]] && (( restart_time <= now && now - restart_time < 3600 )); then
        restart_times+=("$restart_time")
      fi
    done <"$restart_history_file"
  elif (( last_restart > 0 && last_restart <= now && now - last_restart < 3600 )); then
    restart_times+=("$last_restart")
  fi
  save_restart_history
}
save_restart_history() {
  printf '%s\n' "${restart_times[@]}" >"$restart_history_file.tmp.$$"
  mv -f -- "$restart_history_file.tmp.$$" "$restart_history_file"
}
probe_direct() {
  local path=$1 output
  # ShellCheck cannot see that these variables expand inside the proxy container.
  # shellcheck disable=SC2016
  if ! output=$(timeout 12s docker compose -f "$compose_file" exec -T reverse-proxy sh -c '
    timeout 7s wget -qO- -T 5 "$1"
    result=$?
    printf "\nAPP_HEALTH_PROBE_EXIT=%s\n" "$result"
  ' sh "http://$active_service:8080/actuator/health/$path" 2>/dev/null); then
    return 2
  fi
  [[ "$output" == *APP_HEALTH_PROBE_EXIT=* ]] || return 2
  is_up "$output"
}
capture_diagnostics() {
  local snapshot_started snapshot
  snapshot_started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  mkdir -p -- "$diagnostic_dir"
  chmod 700 -- "$diagnostic_dir"
  snapshot="$diagnostic_dir/app-health-$(date -u +%Y%m%dT%H%M%SZ)-$active_service.txt"
  (
    umask 077
    {
      printf 'Captured: %s\nService: %s\nFailed checks: %s\nContainer: %s\n' \
        "$snapshot_started" "$active_service" "$failures" "$container_id"
      timeout 5s docker inspect --format 'State={{.State.Status}} OOMKilled={{.State.OOMKilled}} Restarts={{.RestartCount}} PidsLimit={{.HostConfig.PidsLimit}} MemoryLimit={{.HostConfig.Memory}}' "$container_id" || true
      # shellcheck disable=SC2016
      timeout 5s docker exec "$container_id" sh -c '
        printf "Threads: "; grep "^Threads:" /proc/1/status || true
        for name in pids.current pids.max pids.events memory.current memory.max memory.events; do
          printf "%s: " "$name"
          tr "\n" " " <"/sys/fs/cgroup/$name" 2>/dev/null || true
          printf "\n"
        done
      ' || true
      printf '\nRecent JVM output, limited to 64 KiB:\n'
      if timeout 5s docker kill --signal=SIGQUIT "$container_id" >/dev/null 2>&1; then
        sleep 1
      else
        printf 'Could not request JVM thread dump.\n'
      fi
      timeout 10s docker logs --since "$snapshot_started" "$container_id" 2>&1 | tail -c 65536 || true
    } >"$snapshot"
  )
  log "Saved pre-restart diagnostics: $snapshot"
  find "$diagnostic_dir" -maxdepth 1 -type f -name 'app-health-*.txt' -mtime +7 -delete || true
}

if [[ ! "$failure_threshold" =~ ^[1-9][0-9]*$ || ! "$cooldown_seconds" =~ ^[0-9]+$ || ! "$startup_grace_seconds" =~ ^[0-9]+$ || ! "$max_restarts_per_hour" =~ ^[1-9][0-9]*$ ]]; then
  log "Invalid app health threshold, cooldown, grace, or restart limit" >&2
  exit 2
fi
if [[ -n "$alert_email" && ! "$alert_email" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  log "Invalid app health alert email" >&2
  exit 2
fi

mkdir -p -- "$(dirname -- "$lock_file")" "$(dirname -- "$state_file")" "$(dirname -- "$restart_history_file")"
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
alert_stage=0
if [[ -f "$state_file" ]]; then
  read -r failures last_restart previous_service restart_pending alert_stage <"$state_file" || true
fi
if [[ ! "$failures" =~ ^[0-9]+$ || ! "$last_restart" =~ ^[0-9]+$ || "$previous_service" != "$active_service" ]]; then
  failures=0
  last_restart=0
  restart_pending=0
  alert_stage=0
fi
[[ "$restart_pending" =~ ^[01]$ ]] || restart_pending=0
[[ "$alert_stage" =~ ^[012]$ ]] || alert_stage=0

if response=$(curl --silent --show-error --fail --connect-timeout 2 --max-time 5 "$health_url" 2>/dev/null) && is_up "$response"; then
  if (( failures > 0 || restart_pending == 1 )); then
    log "App health recovered: $active_service"
  elif [[ ${APP_HEALTH_QUIET:-0} != 1 ]]; then
    log "App health OK: $active_service"
  fi
  if (( alert_stage > 0 )); then
    notify_operator "CoZaDzban app recovered" "App service $active_service is responding again."
  fi
  failures=0
  restart_pending=0
  alert_stage=0
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
log "App health failed: $active_service ($failures consecutive failed checks; restart threshold $failure_threshold)"
if (( failures < failure_threshold )); then
  exit 1
fi
if (( alert_stage == 0 )); then
  notify_operator "CoZaDzban site unavailable" "The site health endpoint failed $failures consecutive checks while $active_service was active."
  alert_stage=1
  save_state
fi

if (( now - last_restart < cooldown_seconds )); then
  log "App restart skipped: cooldown remains after the last restart" >&2
  exit 1
fi

load_restart_history
if (( ${#restart_times[@]} >= max_restarts_per_hour )); then
  if (( alert_stage < 2 )); then
    notify_operator "CoZaDzban restart limit reached" "App service $active_service is still unavailable after $max_restarts_per_hour restart attempts in one hour; automatic restarts are paused."
    alert_stage=2
    save_state
  fi
  log "App restart skipped: $max_restarts_per_hour attempts in the past hour" >&2
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
if probe_direct readiness; then
  log "App restart skipped: direct app probe succeeded; investigate the proxy" >&2
  exit 1
else
  probe_result=$?
  if (( probe_result == 2 )); then
    log "App restart skipped: could not run the direct readiness probe" >&2
    exit 1
  fi
fi
if probe_direct liveness; then
  log "App restart skipped: app is live but not ready; investigate its dependencies" >&2
  exit 1
else
  probe_result=$?
  if (( probe_result == 2 )); then
    log "App restart skipped: could not run the direct liveness probe" >&2
    exit 1
  fi
fi

capture_diagnostics || log "Could not capture all pre-restart diagnostics" >&2
log "Restarting unresponsive app service: $active_service"
last_restart=$(date +%s)
restart_times+=("$last_restart")
save_restart_history
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
