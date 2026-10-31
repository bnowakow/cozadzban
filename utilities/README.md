# Utilities

- [Codex commit](#codex-commit) — stage changes, ask Codex for a commit message, commit, and optionally push.
- [Claude commit](#claude-commit) — the same flow with Claude Code instead of Codex.
- [Facebook feed auto-scroll](#facebook-feed-auto-scroll) — browser console snippet that scrolls Facebook to load more posts.
- [Seed sample articles](#seed-sample-articles) — bulk-POST sample URLs to the local API for UI / perf testing.

## Codex commit

`codex-commit.sh` automates the local commit flow from the repo root:

```bash
./utilities/codex-commit.sh
```

The script checks whether the upstream branch has new commits, optionally updates with `git pull --rebase --autostash`, checks for a version bump, stages all current changes, asks the local `codex` command to suggest a commit message using the repository commit-message skill, creates the commit, checks the upstream branch again, and optionally pushes.

If the upstream branch has new commits, the script can run `git pull --rebase --autostash` before version bumping and committing. When that pull produces conflicts, it asks Codex to resolve the conflict markers and then continues the rebase if the tree is clean.

### Requirements

`git` must be on `PATH`. The script uses `codex` from `PATH` when available, honors `CODEX_BIN` as an explicit override, and on macOS also falls back to the bundled CLI inside either `Codex.app` or `ChatGPT.app` under `/Applications` or `~/Applications`. `dialog` or `whiptail` is optional for full-screen terminal prompts; without one, or when the current process has no usable TTY/TERM for curses, prompts fall back to plain terminal input with a diagnostic.

Both `Contents/Resources/codex` and `Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex` bundle layouts are supported. When there are changes to commit, CLI availability is checked before syncing upstream, bumping the version, or staging files.

## Claude commit

`claude-commit.sh` runs the same flow as `codex-commit.sh` (it sets `COMMIT_AGENT=claude` and calls that script), but asks Claude Code for the commit message and for pull-conflict resolution:

```bash
./utilities/claude-commit.sh   # or: make claude-commit
```

Claude runs non-interactively with `claude -p`. For the commit message it may only read files and run read-only git commands (`status`, `diff`, `log`, `show`, `ls-files`). For conflict resolution it may also edit files (`--permission-mode acceptEdits`). In both modes `git commit`, `push`, `reset`, `rebase`, `checkout`, `restore` and `stash` are denied, so the script keeps control of the rebase.

### Requirements

The script uses `claude` from `PATH` when available, honors `CLAUDE_BIN` as an explicit override, and also checks `~/.local/bin/claude`, `~/.claude/local/claude`, `/opt/homebrew/bin/claude` and `/usr/local/bin/claude`. Claude Code must already be signed in (run `claude` once interactively). The other requirements are the same as for [Codex commit](#codex-commit).

## Facebook feed auto-scroll

Paste `facebook-feed-autoscroll.js` into the browser developer console while Facebook is open. It scrolls the feed for 2 minutes to load more posts.

To extend the 2 minute period, edit this line in the script:

```javascript
const durationMs = 2 * 60 * 1000;
```

For example, use `5 * 60 * 1000` for 5 minutes.

To stop it early, run:

```javascript
clearInterval(window.facebookFeedAutoScrollTimer);
window.facebookFeedAutoScrollTimer = null;
```

## Seed sample articles

`seed-sample-articles.sh` reads `sample-articles.json` and POSTs each entry to `{BASE_URL}/api/articles` using a machine-to-machine API key. Useful for populating a local instance with a realistic mix of articles (currently `pl`, `en`, and `de`) for UI and performance testing.

### Quick start

From the repo root, with the app running on `http://localhost:8080`:

```bash
./utilities/seed-sample-articles.sh
```

Existing URLs return `409` and are reported as `skipped` — re-running the script is safe.

### Auth

The script resolves the M2M key from the process environment first, then from `<repo-root>/.env`, in this order:

| Variable | Purpose |
| --- | --- |
| `M2M_KEY` | API key value (preferred override) |
| `APP_MACHINE_AUTH_API_KEY` | Same key as configured on the server |
| `APP_FACEBOOK_IMPORT_TARGET_API_KEY` | Fallback, reuses the FB importer's key |
| `M2M_HEADER` / `APP_MACHINE_AUTH_HEADER_NAME` / `APP_FACEBOOK_IMPORT_TARGET_API_KEY_HEADER` | Header name (default `X-CoZaDzban-M2M-Key`) |

On the server side, `.env` must include matching values:

```
APP_MACHINE_AUTH_ENABLED=true
APP_MACHINE_AUTH_API_KEY=<same value the client sends>
APP_MACHINE_AUTH_PRINCIPAL_EMAIL=<existing ACTIVE app_user email>
```

### Safety guards

`BASE_URL` is intentionally **not** read from `.env` (the FB importer's target may point at prod). It defaults to `http://localhost:8080`.

- Targeting `cozadzban.pl` (any subdomain) prints a red PRODUCTION warning and requires typing `yes` interactively, or `CONFIRM_PRODUCTION=yes`.
- Targeting any other non-localhost host is refused unless `ALLOW_NON_LOCAL=1`.

### Options

| Env var | Default | Description |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | Target API base URL |
| `ENV_FILE` | `<repo-root>/.env` | `.env` file to read auth from |
| `INPUT_FILE` | `utilities/sample-articles.json` | Input JSON array |
| `SLEEP_MS` | `250` | Pause between requests (enrichment hits live URLs) |
| `CONFIRM_PRODUCTION` | _(unset)_ | Set to `yes` to skip the prod confirmation prompt |
| `ALLOW_NON_LOCAL` | _(unset)_ | Set to `1` to allow non-localhost, non-prod hosts |

### Requirements

`jq` and `curl` must be on `PATH`.
