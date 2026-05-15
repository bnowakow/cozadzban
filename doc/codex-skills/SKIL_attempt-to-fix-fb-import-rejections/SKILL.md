---
name: attempt-to-fix-fb-import-rejections
description: Attempt to fix FB import rejections by reviewing rejected URL artifacts in logs/facebook-import-rejections, asking the user why each candidate URL was a bad decision, then using the artifact plus the user's explanation to fix the importer and add diagnostics when needed.
---

# Attempt To Fix FB Import Rejections

Use this skill when the user wants to investigate or fix bad Facebook import candidate URL decisions recorded in `logs/facebook-import-rejections`.

## Workflow

1. List unprocessed artifacts with `find logs/facebook-import-rejections -maxdepth 1 -type f -name '*.json' | sort`.
2. For each artifact, read the JSON and extract at least:
   - `candidateId`
   - `url`
   - `sourcePostUrl`
   - `candidateTextPreview` or `candidateText`
   - `urlSelectionDiagnostics`
3. Present exactly one artifact at a time to the user. Include:
   - a clickable candidate URL: `[candidate URL](...)`
   - a clickable Facebook post URL when present: `[Facebook post](...)`
   - the artifact file path
   - a short candidate text preview
4. Ask the user why the candidate URL was a bad decision before editing code.
5. After the user answers, infer the failure mode from the artifact and the answer. Inspect the importer, URL selection code, and related tests before editing.
6. Fix the behavior in the narrowest place that owns the bad decision.
7. Add or update focused tests covering the rejected artifact's failure mode.
8. Add new diagnostics/log fields only when they would make the next rejection easier to explain. Keep logs free of secrets and token material.
9. Run `make test` before declaring the fix complete.
10. After a log has been processed and the resulting fix is complete, move that JSON file into `logs/facebook-import-rejections/processed/` so it is not reviewed again. Create the directory if needed.

## Review Prompt Shape

Use this concise shape for each artifact:

```markdown
Reviewing `logs/facebook-import-rejections/<file>.json`

Candidate: [<url>](<url>)
Facebook post: [<sourcePostUrl>](<sourcePostUrl>)
Candidate id: `<candidateId>`

Preview: <candidateTextPreview>

Why was this a bad decision?
```

If `sourcePostUrl` is absent, write `Facebook post: unavailable`.

## Fixing Guidance

- Prefer existing importer helpers and tests over adding new abstractions.
- Keep URL parsing and filtering deterministic; avoid network calls in unit tests.
- Treat `candidateText` as noisy browser text. Test against compact examples that preserve the failure signal rather than copying huge logs.
- If the bad decision came from choosing a generic homepage, profile URL, tracking redirect, unrelated embedded URL, or source-post URL, encode that as an explicit selection or rejection rule.
- Do not delete or rewrite existing rejection artifacts unless the user explicitly asks. Moving processed artifacts into `logs/facebook-import-rejections/processed/` is expected.

## Project Rules

Follow repository instructions while fixing:

- all DB access must remain parameterized
- no DDL outside Flyway migrations
- no OAuth tokens in frontend JavaScript or browser storage
- persisted timestamps stay UTC
- RSS must not expose creator/user data
