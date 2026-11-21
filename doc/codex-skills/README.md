# Codex and Claude Skills

Repository-provided skills live in `doc/codex-skills/SKIL_*`. The same `SKILL.md` files work for both Codex and Claude Code.

| | Codex | Claude Code |
| --- | --- | --- |
| Install | `make install-codex-skills` | `make install-claude-skills` |
| Installed to | `$CODEX_HOME/skills/SKIL_<name>` (default `~/.codex`) | `$CLAUDE_CONFIG_DIR/skills/<name>` (default `~/.claude`) |
| Show sample prompts | `make codex-skill-prompts` | `make claude-skill-prompts` |
| Invoke | Use the sample prompt | `/<name>` or the sample prompt |

## Available Skills

| Skill | Description | Prompt |
| --- | --- | --- |
| `attempt-to-fix-fb-import-rejections` | Reviews rejected Facebook import candidate artifacts, asks why each URL was wrong, then fixes importer behavior and diagnostics when needed. | `Attempt to fix FB import rejections from logs/facebook-import-rejections.` |
| `bump-dependencies` | Upgrades dependencies to the latest stable releases that preserve existing compatibility, including compatible CVE fixes when requested. | `Use the bump-dependencies skill to upgrade all dependencies to their latest backwards-compatible releases and verify the result.` |
| `check-sync-between-code-and-documentation` | Audits code and docs for drift, fixes clear mismatches, validates docs, and runs the project tests. | `Use the check-sync-between-code-and-documentation skill to compare the code and docs.` |
| `suggest-commit-message` | Inspects git status, staged and unstaged diffs, untracked files, and recent commits to propose a concise commit message. | `Use the suggest-commit-message skill to propose a concise commit message for the current git changes.` |
| `upgrade-java-lts` | Updates the Java toolchain, CI, and runtime to the latest released Java LTS and verifies them together. | `Use the upgrade-java-lts skill to move this project to the latest generally available Java LTS and verify the build, tests, and runtime.` |
