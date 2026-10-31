#!/usr/bin/env bash
# Sign Claude Code in to your Claude subscription inside the Spring Boot container. The login is
# saved in the claude-auth volume shared by the blue and green containers.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

compose=(docker compose -f compose.yaml)
running_services=$("${compose[@]}" ps --status running --services)

# The CLI prints a sign-in URL; open it on your own device and paste the code back here.
if grep -Fxq springboot <<<"$running_services"; then
  "${compose[@]}" exec springboot claude auth login --claudeai
elif grep -Fxq springboot-green <<<"$running_services"; then
  "${compose[@]}" --profile green exec springboot-green claude auth login --claudeai
else
  "${compose[@]}" run --rm --no-deps --entrypoint claude springboot auth login --claudeai
fi

# Verify from a different container, so a login that was not saved to the shared volume is caught.
# Capture the output first: piping into `grep -q` can end the pipe early, and with pipefail the
# resulting broken-pipe exit from docker would make a good login look like a failure.
for attempt in 1 2 3 4 5; do
  status_output=$("${compose[@]}" --profile ops run --rm --no-deps -T claude-auth-check auth status --json 2>/dev/null || true)
  if grep -Eq '"loggedIn"[[:space:]]*:[[:space:]]*true' <<<"$status_output"; then
    echo 'Claude login is available from the shared Docker volume.'
    exit 0
  fi
  sleep 2
done

echo 'Claude reported login success, but another container cannot read it. Check the claude-auth volume mount.' >&2
exit 1
