#!/usr/bin/env bash
# Claude Code variant of codex-commit.sh: same flow, but Claude writes the
# commit message and resolves pull conflicts.
set -euo pipefail

COMMIT_AGENT=claude exec "$(dirname "${BASH_SOURCE[0]}")/codex-commit.sh" "$@"
