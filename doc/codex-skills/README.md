# Codex Skills

Repository-provided Codex skills live in `doc/codex-skills/SKIL_*` and can be installed with:

```sh
make install-codex-skills
```

Show all sample prompts with:

```sh
make codex-skill-prompts
```

## Available Skills

| Skill | Description | Prompt |
| --- | --- | --- |
| `attempt-to-fix-fb-import-rejections` | Reviews rejected Facebook import candidate artifacts, asks why each URL was wrong, then fixes importer behavior and diagnostics when needed. | `Attempt to fix FB import rejections from logs/facebook-import-rejections.` |
| `check-sync-between-code-and-documentation` | Audits code and docs for drift, fixes clear mismatches, validates docs, and runs the project tests. | `Use the check-sync-between-code-and-documentation skill to compare the code and docs.` |
| `suggest-commit-message` | Inspects git status, staged and unstaged diffs, untracked files, and recent commits to propose a concise commit message. | `Use the suggest-commit-message skill to propose a concise commit message for the current git changes.` |
