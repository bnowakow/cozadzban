---
name: check-sync-between-code-and-documentation
description: Audit and align a repository's implementation with its documentation. Use when the user asks to read the code and docs, check whether they are in sync/up to date, compare API/spec/README/manual docs against implementation, fix clear drift in code or docs, validate documentation syntax, and run the project's required tests.
---

# Check Sync Between Code And Documentation

## Goal

Verify that code and documentation describe the same system, then fix clear mismatches with the smallest safe changes. Treat docs and code as peers: sometimes docs are stale, sometimes code is missing documented behavior.

## Workflow

1. Map the repo before judging.
   - Read `AGENTS.md`, `README*`, `doc/`, build files, routes/controllers, migrations, config, tests, and important domain services.
   - Use `rg --files`, `find doc -type f`, `git status --short`, and targeted `sed`/`rg`.
   - Note dirty worktree changes and do not revert user work.

2. Build a comparison table mentally or in notes.
   - Public API: routes, methods, auth, request/response DTOs, status codes, error shapes.
   - Database: migrations, schema docs, indexes, constraints.
   - Security: auth flows, token/session handling, CSRF/CORS, roles, machine credentials.
   - UI: documented views, visibility rules, actions, form fields.
   - Runtime/config: env vars, profiles, build metadata, Docker/compose behavior.
   - Background jobs/importers and any internal helper endpoints.

3. Validate documentation syntax.
   - Parse YAML/OpenAPI files with a local parser when available.
   - Check that referenced schemas/security schemes exist.
   - Look for duplicate or malformed path/method entries and stale examples.

4. Decide the direction of each fix.
   - If code implements working behavior and docs are stale, update docs.
   - If docs specify an important contract and code almost satisfies it, prefer a small code fix plus tests.
   - If the mismatch is ambiguous or product-significant, report it instead of inventing policy.
   - Never edit applied Flyway migrations; add a new migration when schema changes are needed.

5. Make scoped edits.
   - Keep changes close to the mismatch.
   - Preserve public compatibility when possible; document compatibility endpoints if they remain.
   - Keep SQL parameterized and follow repo rules.
   - Update both source-of-truth docs and derived/runtime docs when both exist.

6. Verify.
   - Run doc syntax checks used during the audit.
   - Run the project-required test suite. In this repo that is `make test` or `./gradlew test`.
   - If tests fail, fix failures before finishing unless blocked.

## Common Findings To Check

- OpenAPI/YAML syntax errors.
- Code-only endpoints missing from docs.
- Documented endpoints or fields not accepted by DTOs/controllers.
- Auth docs that omit machine/session variants.
- Schema/docs limits that differ from constants or migrations.
- README env vars that are no longer present in config.
- Security claims that contradict `SecurityConfig`.
- UI docs that omit admin-only or authenticated-only behavior.

## Final Response

Summarize what was audited, mismatches found, code/docs changed, validation commands and results, and any ambiguity left for user decision.
