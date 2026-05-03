# CoZaZjeb — Implementation Plan

Bottom-up plan respecting compile-time dependencies.
Phases 4 and 5 are independent and can be developed in parallel.

---

## Phase 1 — Project foundation

1. **`application.properties`** — configure datasource, Flyway, JWT issuer/audience, CORS,
   `app.build.timestamp` placeholder, Actuator
2. **`compose.yaml`** — verify it wires `POSTGRES_*` env vars from `.env`
3. **Gradle `processResources`** — inject `app.build.timestamp` as ISO-8601 UTC via `expand()`

---

## Phase 2 — Database migrations

4. **`V1__create_articles.sql`** — `article` table: `id`, `url` (UNIQUE), `language`, `title`,
   `thumbnail`, `lead`, `quote`, `ai_summary`, `created_at` + indexes `article_language_idx`,
   `article_created_at_idx`
5. **`V2__create_app_users.sql`** — `app_user` table: `id`, `email` (UNIQUE), `role`,
   `created_at` + index `app_user_email_idx`

---

## Phase 3 — Domain model & persistence

6. **`Article` data class** — maps to `article` table (`@Table`, Spring Data JDBC)
7. **`AppUser` data class** — maps to `app_user` table
8. **`ArticleRepository`** — `CrudRepository` + custom `NamedParameterJdbcTemplate` query for
   paginated/sorted list (sort field validated against allowlist before interpolation)
9. **`AppUserRepository`** — `CrudRepository` + `findByEmail`, `countByRole`

---

## Phase 4 — Security  *(parallel with Phase 5)*

10. **`SecurityConfig`** — resource server JWT (Google OIDC), CORS config, CSRF rules
    (`/api/**` and `/rss` disabled), `denyByDefault`, custom `JwtAuthenticationConverter`
    reading `email` + `email_verified` claims
11. **`AllowlistAuthorizationManager`** — loads `app_user` row by normalized email,
    checks role; used as method-security guard on write endpoints
12. **`BootstrapAdminService`** — `ApplicationReadyEvent` listener: counts ADMIN rows,
    reads `COZAZJEB_BOOTSTRAP_ADMIN_EMAIL`, seeds/promotes or fails fast (BR-20)

---

## Phase 5 — URL enrichment  *(parallel with Phase 4)*

13. **`EnrichmentService`** — `RestClient` (connect 3 s, read 5 s, no retries), fetches URL,
    parses `og:title` / `<title>`, `og:image`, `og:description` / `<meta name="description">`;
    throws typed exception on non-2xx / timeout / unreachable → caller responds 422

---

## Phase 6 — Article API

14. **`ArticleService`** — URL canonicalization (BR-14), enrichment orchestration, conflict
    detection → 409 `ProblemDetail` with `articleUrlConflict` type URI
15. **`ArticleController`** — `POST`, `GET` list (pagination + sort allowlist), `GET /{id}`,
    `PUT`, `PATCH` (`application/merge-patch+json`), `DELETE`
16. **`GlobalExceptionHandler`** — `@RestControllerAdvice` translating validation, enrichment,
    conflict and auth exceptions to `application/problem+json` (RFC 9457)

---

## Phase 7 — Allowlist API

17. **`AppUserService`** — email normalisation (trim + lowercase), duplicate check → 409
    `allowlistEmailConflict`, last-admin invariant → 409 `lastAdminRequired` (BR-23)
18. **`AppUserController`** — `GET`, `POST`, `DELETE /{id}`, `PATCH /{id}` (role update);
    all require ADMIN role

---

## Phase 8 — RSS feed

19. **`RssController`** — `GET /rss`, builds RSS 2.0 XML, applies `?lang=` filter (free-form
    BCP-47), converts `app.build.timestamp` ISO-8601 → RFC-822 for `lastBuildDate`,
    articles ordered `created_at DESC`; fails fast on missing/unparseable timestamp (BR-22)

---

## Phase 9 — Vaadin UI

20. **`ArticleListView`** — `@Route("")`, `Grid<Article>`, server-side `DataProvider`,
    pagination with page-size selector `[10, 20, 40, 60, 80, 100]`, sort on allowlisted
    columns `[id, createdAt, language, title, url]`, row click opens URL in new tab

---

## Phase 10 — Tests

21. **Slice tests** — `@WebMvcTest` per controller (mock service layer): happy paths +
    400 / 401 / 403 / 404 / 409 / 422 per endpoint
22. **Integration tests** — `@SpringBootTest` + Testcontainers (already scaffolded):
    Flyway migrations, bootstrap logic, end-to-end article lifecycle
