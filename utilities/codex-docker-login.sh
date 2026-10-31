#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

compose=(docker compose -f compose.yaml)
running_services=$("${compose[@]}" ps --status running --services)

# Keep the login process in a running Java container when one is available.
# Both blue and green containers use the same persistent Codex auth volume.
if grep -Fxq springboot <<<"$running_services"; then
  "${compose[@]}" exec springboot codex login --device-auth
elif grep -Fxq springboot-green <<<"$running_services"; then
  "${compose[@]}" --profile green exec springboot-green codex login --device-auth
else
  "${compose[@]}" run --rm --no-deps --entrypoint codex springboot login --device-auth
fi

# Verify in a different container. This catches a login that was displayed as
# successful but was not persisted to the shared volume.
# Capture the output first: piping into `grep -q` can end the pipe early, and with pipefail the
# resulting broken-pipe exit from docker would make a good login look like a failure.
for attempt in 1 2 3 4 5; do
  status_output=$("${compose[@]}" --profile ops run --rm --no-deps -T codex-auth-check login status 2>/dev/null || true)
  if grep -Fxq 'Logged in using ChatGPT' <<<"$status_output"; then
    echo 'Codex ChatGPT login is available from the shared Docker volume.'
    exit 0
  fi
  sleep 2
done

echo 'Codex reported login success, but another container cannot read it. Check the codex-auth volume mount.' >&2
exit 1
