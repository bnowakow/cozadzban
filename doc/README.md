# doc/

Project documentation, specs, and developer aids.

## Specifications

- **[SPEC.yaml](SPEC.yaml)** — single source of truth for the system. OpenAPI 3.0 with `x-`
  extensions covering architecture, database schema, business rules, and UI specs. Intended
  audience: LLMs used for code generation and review.
- **[openapi.yaml](openapi.yaml)** — runtime API contract served by springdoc. Describes the REST
  endpoints, RSS feed, and request/response schemas.

## Plans and testing

- **[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** — bottom-up, phased implementation plan
  respecting compile-time dependencies.
- **PHASE_\*\_MANUAL_TESTING.md** — manual test guides for specific phases:
  [11](PHASE_11_MANUAL_TESTING.md), [13](PHASE_13_MANUAL_TESTING.md),
  [16](PHASE_16_MANUAL_TESTING.md), [17](PHASE_17_MANUAL_TESTING.md),
  [24](PHASE_24_MANUAL_TESTING.md). Phase 24 covers the Facebook proposal inbox,
  Selenium import, and scheduled Spring Batch worker/server modes.

## Developer aids

- **[dev-test-data.sql](dev-test-data.sql)** — sample articles with long titles for UI
  evaluation. Load with `psql -h localhost -U cozadzban -d cozadzban -f doc/dev-test-data.sql`
  after at least one `app_user` row exists.
- **[codex-skills/](codex-skills/README.md)** — repository-provided Codex skills installable via
  `make install-codex-skills`.
