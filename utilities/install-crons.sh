#!/usr/bin/env bash
set -euo pipefail

mode=${1:-all}
case "$mode" in
  postgres|codex|claude|app-health|all) ;;
  *) echo "Usage: $0 {postgres|codex|claude|app-health|all}" >&2; exit 2 ;;
esac

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
make_bin=${CRON_MAKE:-$(command -v make)}
pg_schedule=${CRON_SCHEDULE:-0 2 * * *}
codex_schedule=${CODEX_AUTH_CRON_SCHEDULE:-15 3 * * *}
claude_schedule=${CLAUDE_AUTH_CRON_SCHEDULE:-30 3 * * *}
app_health_schedule=${APP_HEALTH_CRON_SCHEDULE:-* * * * *}
pg_marker=cozadzban-docker-pg-backup
pg_legacy_marker=cozazjeb-docker-pg-backup
codex_marker=cozadzban-codex-auth-check
claude_marker=cozadzban-claude-auth-check
app_health_marker=cozadzban-app-health-check

if current=$(crontab -l 2>&1); then
  :
elif [[ "$current" == *"no crontab for"* ]]; then
  current=''
else
  printf 'Cannot read existing crontab: %s\n' "$current" >&2
  exit 1
fi

cron_file=$(mktemp)
trap 'rm -f "$cron_file"' EXIT
chmod 600 "$cron_file"

if [[ -n "$current" ]]; then
  while IFS= read -r line; do
    if [[ "$mode" == postgres || "$mode" == all ]]; then
      if [[ "$line" == *"# $pg_marker" || "$line" == *"# $pg_legacy_marker" ]]; then
        continue
      fi
    fi
    if [[ "$mode" == codex || "$mode" == all ]]; then
      if [[ "$line" == *"# $codex_marker" ]]; then
        continue
      fi
    fi
    if [[ "$mode" == claude || "$mode" == all ]]; then
      if [[ "$line" == *"# $claude_marker" ]]; then
        continue
      fi
    fi
    if [[ "$mode" == app-health || "$mode" == all ]]; then
      if [[ "$line" == *"# $app_health_marker" ]]; then
        continue
      fi
    fi
    printf '%s\n' "$line" >>"$cron_file"
  done <<<"$current"
fi

if [[ "$mode" == postgres || "$mode" == all ]]; then
  mkdir -p "$repo_root/docker-data/backup/postgres"
  printf '%s cd %q && %q docker-pg-backup >> %q 2>&1 # %s\n' \
    "$pg_schedule" "$repo_root" "$make_bin" \
    "$repo_root/docker-data/backup/postgres/cron.log" "$pg_marker" >>"$cron_file"
fi

if [[ "$mode" == codex || "$mode" == all ]]; then
  mkdir -p "$repo_root/logs"
  printf '%s cd %q && %q --no-print-directory -s codex-auth-check >> %q 2>&1 || echo %q # %s\n' \
    "$codex_schedule" "$repo_root" "$make_bin" \
    "$repo_root/logs/codex-auth-check.log" \
    "Codex authorization check failed; see $repo_root/logs/codex-auth-check.log" \
    "$codex_marker" >>"$cron_file"
fi

if [[ "$mode" == claude || "$mode" == all ]]; then
  mkdir -p "$repo_root/logs"
  printf '%s cd %q && %q --no-print-directory -s claude-auth-check >> %q 2>&1 || echo %q # %s\n' \
    "$claude_schedule" "$repo_root" "$make_bin" \
    "$repo_root/logs/claude-auth-check.log" \
    "Claude authorization check failed; see $repo_root/logs/claude-auth-check.log" \
    "$claude_marker" >>"$cron_file"
fi

if [[ "$mode" == app-health || "$mode" == all ]]; then
  mkdir -p "$repo_root/logs"
  printf '%s cd %q && APP_HEALTH_QUIET=1 %q --no-print-directory -s app-health-check >> %q 2>&1 # %s\n' \
    "$app_health_schedule" "$repo_root" "$make_bin" \
    "$repo_root/logs/app-health-check.log" "$app_health_marker" >>"$cron_file"
fi

crontab "$cron_file"

case "$mode" in
  postgres) echo "Installed PostgreSQL backup cron at $pg_schedule" ;;
  codex) echo "Installed Codex authorization cron at $codex_schedule" ;;
  claude) echo "Installed Claude authorization cron at $claude_schedule" ;;
  app-health) echo "Installed app health cron at $app_health_schedule" ;;
  all) echo "Installed PostgreSQL backup ($pg_schedule), Codex authorization ($codex_schedule), Claude authorization ($claude_schedule), and app health ($app_health_schedule) crons" ;;
esac
