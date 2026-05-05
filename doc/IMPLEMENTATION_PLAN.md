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

## Phase 11 — Backend auth endpoints (NEW)

23. **`UiAuthController` + OAuth2 login config** — secure first-party login flow:
    - `GET /auth/login` — redirects to Google OIDC authorization endpoint
      using Authorization Code + PKCE (no tokens exposed to frontend JS)
    - OAuth callback handled by Spring Security (`/login/oauth2/code/google`)
    - `GET /auth/me` — returns authenticated user details (email, role) for UI shell
      from server-side SecurityContext; returns 401 if unauthenticated
    - `POST /auth/logout` — invalidates server session and returns 204 No Content
24. **`UiPrincipalMapper`** — maps authenticated Google principal to app role:
    - normalize email (trim + lowercase)
    - resolve role from `app_user` allowlist table
    - deny write-capable UI actions when email not allowlisted
25. **Session cookie + CSRF configuration**:
    - session cookie `JSESSIONID`: HttpOnly, Secure (prod), SameSite=Lax, Path=/
    - CSRF enabled for UI session-backed endpoints (`/auth/logout`, Vaadin internal requests)
    - no OAuth token material stored in localStorage/sessionStorage

---

## Phase 12 — Admin panel UI (NEW)

26. **`AdminView`** — `@Route("/admin")`, `@RolesAllowed("ADMIN")` (Vaadin navigation access control; `@PreAuthorize` cannot be used because Kotlin classes are `final` by default and CGLIB cannot proxy them):
        - Users table with columns: id, email, role, createdAt
        - Actions: delete user (if not last admin), update role (USER ↔ ADMIN)
        - Add user form: email input + role selector, submit button
        - Error toast on conflict/validation failure (409, 400)
        - Success toast after add/update/delete
        - Logout button in top-right
        - Uses server-side service calls (`AppUserService`) from Vaadin events,
            not browser-side REST calls with cookies

---

## Phase 13 — Article creation modal (ENHANCEMENT to Phase 9)

27. **Enhanced `ArticleListView`**:
    - Show/hide "Add Article" button based on authentication (visible only when logged in)
    - Click "Add Article" → modal with form (url, language, optional quote)
    - Form validation: url required, language required, quote optional
    - Submit via server-side `ArticleService` call from Vaadin event handler (no browser token handling)
    - Success: close modal, refresh grid, show success toast
    - Failure: show error toast with detail (409 conflict, 422 enrichment failed, etc.)
    - Modal also has "Login with Google" button overlay if user is not authenticated

---

## Phase 10 — Tests

21. **Slice tests** — `@WebMvcTest` per controller (mock service layer): happy paths +
    400 / 401 / 403 / 404 / 409 / 422 per endpoint
28. **Integration tests** — `@SpringBootTest` + Testcontainers (already scaffolded):
    Flyway migrations, bootstrap logic, end-to-end article lifecycle

---

## Phase 14 — Hybrid Auth Regression Test Plan (NEW)

29. **Auth boundary tests (session vs bearer)**:
    - Verify first-party Vaadin UI paths use authenticated server session only
    - Verify REST write endpoints (`/api/articles` write methods, `/api/users/**`) still require Bearer JWT
    - Verify no privilege escalation between session-authenticated UI and bearer-only API calls
30. **OAuth2 login/session lifecycle tests**:
    - `GET /auth/login` redirects to Google OIDC authorization endpoint
    - OAuth callback establishes authenticated session with expected principal and role mapping
    - `GET /auth/me` returns 200 with `{email, role}` for valid session and 401 otherwise
    - `POST /auth/logout` invalidates session and subsequent `/auth/me` returns 401
31. **Session security hardening tests**:
    - Validate `JSESSIONID` cookie flags: HttpOnly, Secure (prod profile), SameSite policy, Path=/
    - Validate session fixation protection on successful login
    - Validate idle timeout and absolute timeout behavior (session expires as configured)
32. **CSRF regression tests for cookie-authenticated UI flows**:
    - Verify CSRF token is required on session-backed state-changing endpoints
    - Verify requests missing/invalid CSRF token are rejected (403)
    - Verify stateless bearer-token API endpoints keep intended CSRF behavior (`/api/**`, `/rss`)
33. **RBAC + allowlist mapping tests for UI actions**:
    - Verify `UiPrincipalMapper` normalizes email and resolves role from `app_user`
    - Verify non-allowlisted authenticated users cannot perform write-capable UI actions
    - Verify `@Route("/admin")` is ADMIN-only and USER is denied
34. **End-to-end UI security regression scenarios**:
    - Anonymous user: can view article list, cannot see/submit privileged actions
    - Authenticated USER: can open add-article modal and submit article via server-side service path
    - Authenticated ADMIN: can manage users in `AdminView` while preserving last-admin invariant
    - Regression guard: no OAuth tokens appear in browser storage (`localStorage`/`sessionStorage`)
