---
name: suggest-commit-message
description: Inspect a repository's git status, staged and unstaged diffs, untracked files, and recent committed context, then propose a short commit message the user can use when committing.
---

# Suggest Commit Message

Use this skill when the user wants a concise commit message based on the current repository changes.

## Workflow

1. Inspect the working tree without changing it.
   - Run `git status --short`.
   - Run `git diff --stat`, `git diff --name-status`, and `git diff`.
   - Run `git diff --cached --stat`, `git diff --cached --name-status`, and `git diff --cached`.
   - Run `git ls-files --others --exclude-standard` to find untracked files. Read small untracked files when needed to understand their purpose.
   - Run `git show --stat --oneline --no-renames HEAD` or `git log --oneline -5` when recent commit style helps choose wording.

2. Decide the commit scope.
   - If staged changes exist, propose the message for the staged diff, and briefly note any unstaged or untracked changes left out.
   - If nothing is staged, propose the message for the full local diff against `HEAD`, including relevant untracked files.
   - If the changes are unrelated, propose one message per cohesive commit and say which files belong to each.
   - If only generated or binary files changed, infer the intent from names, surrounding source changes, and recent commits; call out uncertainty instead of guessing too strongly.

3. Write the message.
   - Prefer one short subject line in imperative mood, around 50 characters when practical.
   - Match the repository's recent style when it is clear.
   - Do not include a body unless the change needs context the subject cannot carry.
   - Do not stage, commit, amend, reset, or edit files.

## Response Shape

Start with the recommended message in a copyable code block:

```text
Short imperative commit subject
```

Then add at most two concise notes covering scope, omitted changes, or uncertainty.
