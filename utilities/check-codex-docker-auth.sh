#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

if ! command -v docker >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then
  echo "Codex authorization check failed: docker, jq, and timeout are required." >&2
  exit 1
fi

compose=(docker compose -f compose.yaml --profile ops run --rm --no-deps -T --interactive=false codex-auth-check)

# `login status` checks only whether credentials are cached. A live request
# below also checks that Codex can use them now.
echo 'Checking saved Codex login in Docker...'
if ! running_services=$(timeout --foreground --kill-after=5s 15s docker compose -f compose.yaml ps --status running --services </dev/null 2>&1); then
  echo "Codex authorization check failed: could not list running Docker services." >&2
  exit 1
fi
if grep -Fxq springboot <<<"$running_services"; then
  status_command=(docker compose -f compose.yaml exec -T springboot codex login status)
elif grep -Fxq springboot-green <<<"$running_services"; then
  status_command=(docker compose -f compose.yaml --profile green exec -T springboot-green codex login status)
else
  status_command=("${compose[@]}" login status)
fi

status_code=0
status_output=$(timeout --foreground --kill-after=5s 60s "${status_command[@]}" </dev/null 2>&1) || status_code=$?
if ((status_code != 0)); then
  if [[ "$status_output" == *"Not logged in"* ]]; then
    echo "Codex authorization check failed: the shared Docker volume has no saved login. Run make codex-docker-login." >&2
  elif ((status_code == 124)); then
    echo "Codex authorization check failed: login status timed out after 60 seconds." >&2
  else
    printf 'Codex authorization check failed: could not run login status (exit %s). Check Docker access, then run make codex-docker-status.\n' "$status_code" >&2
  fi
  exit 1
fi
if [[ "$status_output" != *"Logged in using ChatGPT"* ]]; then
  echo "Codex authorization check failed: expected a ChatGPT device login. Run make codex-docker-login." >&2
  exit 1
fi

events_file=$(mktemp)
errors_file=$(mktemp)
trap 'rm -f "$events_file" "$errors_file"' EXIT

echo 'Saved login found. Making a live Codex request (up to 120 seconds)...'
live_code=0
timeout --foreground --kill-after=10s 120s "${compose[@]}" exec \
    --ephemeral --ignore-user-config --ignore-rules --skip-git-repo-check \
    --sandbox read-only --json \
    'This is an authorization health check. Reply with AUTH_OK. Do not use tools.' \
    </dev/null >"$events_file" 2>"$errors_file" || live_code=$?
if ((live_code != 0)); then
  if ((live_code == 124 || live_code == 137)); then
    echo 'Codex authorization check failed: live request timed out after 120 seconds.' >&2
  else
    printf 'Codex authorization check failed: live request exited %s. Check account access and container connectivity.\n' "$live_code" >&2
  fi
  exit 1
fi

if ! jq -e -s 'any(.[]; .type == "turn.completed") and all(.[]; .type != "turn.failed" and .type != "error")' \
    "$events_file" >/dev/null; then
  echo "Codex authorization check failed: no successful turn in the live response." >&2
  exit 1
fi

printf '%s Codex authorization OK: cached ChatGPT login and live request succeeded.\n' \
  "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
