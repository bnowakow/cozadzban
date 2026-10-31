#!/usr/bin/env bash
# Daily check that Claude Code in Docker is signed in and can answer a small live request.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

if ! command -v docker >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then
  echo "Claude authorization check failed: docker and timeout are required." >&2
  exit 1
fi

check=(docker compose -f compose.yaml --profile ops run --rm --no-deps -T --interactive=false claude-auth-check)

echo 'Checking saved Claude login in Docker...'
status_code=0
status_output=$(timeout --foreground --kill-after=5s 60s "${check[@]}" auth status --json </dev/null 2>&1) || status_code=$?
if ((status_code != 0)) || ! grep -Eq '"loggedIn"[[:space:]]*:[[:space:]]*true' <<<"$status_output"; then
  if [[ -n "${APP_FACEBOOK_IMAGE_CLAUDE_OAUTH_TOKEN:-}" ]] || grep -Eq '^APP_FACEBOOK_IMAGE_CLAUDE_OAUTH_TOKEN=.+' .env 2>/dev/null; then
    echo 'No saved login; an OAuth token is configured, so continuing with the live request.'
  else
    echo "Claude authorization check failed: no saved login. Run make claude-docker-login or make claude-docker-token." >&2
    exit 1
  fi
fi

echo 'Making a live Claude request (up to 120 seconds)...'
live_code=0
live_output=$(timeout --foreground --kill-after=10s 120s "${check[@]}" -p --output-format text --tools "" \
    --no-session-persistence --strict-mcp-config \
    'This is an authorization health check. Reply with AUTH_OK only.' </dev/null 2>&1) || live_code=$?
if ((live_code != 0)) || [[ "$live_output" != *AUTH_OK* ]]; then
  if ((live_code == 124 || live_code == 137)); then
    echo 'Claude authorization check failed: live request timed out after 120 seconds.' >&2
  else
    printf 'Claude authorization check failed (exit %s): %s\n' "$live_code" "$(tail -n 1 <<<"$live_output")" >&2
  fi
  exit 1
fi

printf '%s Claude authorization OK: saved login and live request succeeded.\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
