# Agent Instructions

## After every code change
On development machines, including the MacBook, run the test suite before considering the task complete:
```
make test
```
or equivalently:
```
./gradlew test
```
If tests fail, fix the failures before finishing.

Identify the deployment server by running `hostname`: if it prints
`ovh.bnowakowski.pl`, this is the server covered by the no-tests rule (its checkout is
at `/home/sup/docker/cozadzban.pl`). Do not run Gradle tests here, including
`make test` or `./gradlew test`. This server is too weak for them. Use appropriate
checks that do not run tests and state in the handoff that the test suite was not run here.
Never run tests on this deployment server.
Run the suite on a development machine instead when one is available.

## Coding rules (from SPEC.yaml)

### Database
- All DB queries MUST use parameterised statements — raw string concatenation into SQL is
  forbidden (OWASP A03 / BR-09).
- Custom queries via `NamedParameterJdbcTemplate` MUST use named parameters (`:param`), never
  string concatenation.
- No DDL (CREATE TABLE, ALTER TABLE, etc.) outside Flyway migration scripts (BR-06).
- Migration scripts live in `src/main/resources/db/migration/` and follow the naming convention
  `V<version>__<snake_case_description>.sql`.
- Never edit an already-applied Flyway migration. Add a new `V<next_version>__...sql` migration
  instead, then verify it through the Kotlin/Spring runtime by running the test suite on a
  development machine so Flyway validation and migration execution are exercised. On the
  deployment server, report this verification as pending rather than running Gradle tests there.

### Security
- Frontend JavaScript MUST NEVER receive OAuth access tokens or ID tokens; the OAuth callback is
  handled entirely server-side.
- No OAuth token material may be stored in `localStorage` or `sessionStorage`.
- CSRF is disabled only for `/api/**` and `/rss`; it MUST remain enabled for Vaadin UI routes.
- `@PreAuthorize` cannot be used on Kotlin classes (they are `final` by default; CGLIB cannot
  proxy them). Use `@RolesAllowed` for Vaadin view access control instead.

### Architecture
- Persistence uses Spring Data JDBC (not JPA/Hibernate) — no `ddl-auto` settings.
- URL enrichment is synchronous and atomic: if the fetch fails, no DB write occurs (BR-01–03).
- All persisted timestamps are UTC (BR-15).
- RSS MUST never expose creator/user data (BR-07).
