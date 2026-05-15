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
    (expanded in Phase 18 with publishedAt, filters, and creator-aware display)

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
        - Actions: soft-delete user (if not last active admin), update role (USER ↔ ADMIN)
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

---

## Phase 15 — Dockerized application runtime (NEW)

35. **`Dockerfile`** — build the Spring Boot application with Gradle in a Java 21 builder image,
    copy the boot jar into a smaller Java 21 runtime image, expose port 8080, and run as a
    non-root user
36. **`.dockerignore`** — keep local secrets, build outputs, IDE files, and Docker volume data
    out of the image build context
37. **`compose.yaml` springboot service** — build the app image and run it beside PostgreSQL, using
    `jdbc:postgresql://postgres:5432/${POSTGRES_DB}` plus `JDBC_DATABASE_USERNAME` and
    `JDBC_DATABASE_PASSWORD` derived from the existing `POSTGRES_*` variables
38. **PostgreSQL healthcheck** — make the app wait for PostgreSQL readiness through
    `depends_on: condition: service_healthy`
39. **TODO: Nginx server block for production domain** — fix
    `Could not automatically find a matching server block for www.cozazjeb.bnowakowski.pl. Set the server_name directive to use the Nginx installer.`
    by setting the appropriate `server_name` directive for `www.cozazjeb.bnowakowski.pl`
40. **TODO: Google OAuth HTTPS redirect URI** — replace the temporary HTTP callback in Google
    OAuth settings with `https://cozazjeb.bnowakowski.pl/login/oauth2/code/google` once
    HTTPS/DNS is stable

---

## Phase 16 — Article ownership and soft-deleted users (NEW)

41. **`V3__article_creator_and_user_status.sql`** — add `app_user.status` (`ACTIVE`/`DELETED`,
    default `ACTIVE`) and `article.created_by_user_id BIGINT NOT NULL` referencing
    `app_user(id)`. Backfill existing articles to the oldest `app_user` by
    `(created_at ASC, id ASC)`. Migration must fail if articles exist but no user exists.
42. **`AppUser` status model** — add `AppUserStatus`, expose status in admin APIs/UI,
    keep email unique even for `DELETED` users, and make delete a soft-delete operation.
43. **Auth and allowlist checks** — only `ACTIVE` users can log in, create articles, or manage
    resources. Deleted users are denied even if their Google JWT/session is otherwise valid.
    Minimum-admin invariant counts only `ACTIVE ADMIN` users.
44. **User restoration** — admin edit flow can change status from `DELETED` back to `ACTIVE`;
    restoring an ADMIN must preserve the minimum-admin invariant and email uniqueness.
45. **Article creator assignment** — article creation resolves creator from the authenticated
    DB user (JWT email for REST, session principal for Vaadin) and stores only the FK. Creator
    is immutable: `PUT`/`PATCH` must not accept or alter `created_by_user_id`.
46. **Creator-aware article DTOs** — public/anonymous article responses omit creator data.
    Authenticated article responses include `createdBy: { id, email }` in both list and
    detail endpoints (batch-fetched per page for the list). RSS must never expose creator/user data.

---

## Phase 17 — Publication metadata and language normalization (NEW)

47. **`V4__article_published_at.sql`** — add nullable `article.published_at TIMESTAMPTZ` and
    index `article_published_at_idx` for filtering/sorting.
48. **Publication date enrichment** — extend `EnrichmentService` to parse nullable
    `publishedAt`, in order: `meta[property=article:published_time]`, JSON-LD
    `Article`/`NewsArticle.datePublished`, `meta[name=date]`, `meta[property=datePublished]`,
    and `time[datetime]`. Invalid/missing values produce `null`, not enrichment failure.
49. **Thumbnail enrichment hardening** — continue fetching `thumbnail` from `og:image`, resolving
    relative URLs against the article URL where possible.
50. **Manual publication date edits** — `ArticleInput` and `ArticlePatch` accept optional
    `publishedAt`. On create, user-provided `publishedAt` overrides enriched value; omission
    uses enrichment. On update/patch, admins/users may set or clear `publishedAt`.
51. **Language normalization and validation** — normalize language tags to lowercase before
    persistence. Validate against a conservative BCP-47-like pattern
    `^[a-z]{2,3}(-[a-z0-9]{2,8})*$`; invalid values return 400.
52. **Article list query upgrades** — support filters for `language`, `publishedAt` range, and
    `createdAt` range. Add `publishedAt` and `createdAt` to sortable/filterable fields.

---

## Phase 18 — Article list UI filters and editing (NEW)

53. **Article grid columns** — include `publishedAt`, `createdAt`, language, title, thumbnail
    preview/link, URL, and authenticated-only creator display where appropriate.
54. **Language filter** — dropdown populated from distinct normalized article languages with an
    `All` option.
55. **Date range filters** — add date/time range controls for `publishedAt` and `createdAt`.
    Filters apply server-side and compose with pagination and sorting.
56. **Article create/edit modal** — add date picker + time picker for optional `publishedAt`.
    Support clearing the field to persist `null`. Keep creator immutable and hidden from form
    inputs.
57. **RSS discovery in UI** — advertise the feed in page HTML with
    `<link rel="alternate" type="application/rss+xml" title="Co za zjeb RSS" href="/rss">`
    and add a visible RSS icon/link in the article list top bar for users.

---

## Phase 19 — Facebook import job orchestration (NEW)

58. **`FacebookProfileArticleImporter` service** — own a long-lived Selenium WebDriver,
    start imports on demand, reuse the browser between runs, verify Facebook login before
    each scan, and keep at most one active import thread at a time. In remote mode, the
    worker posts new articles to the target server's article API and uses a machine key
    for create/patch requests instead of calling `ArticleService` directly.
59. **`FacebookImportController`** — expose admin-only `POST /api/admin/facebook-import/run`
    and `POST /api/admin/facebook-import/terminate` endpoints for manual or cron-triggered
    job control; return `202` on accepted start/terminate and `409` for busy/not-running.
60. **`ArticleListView` admin action** — add an admin-only "Import Facebook Posts" button
    next to "Add Article" that calls the import trigger endpoint/service from the UI.
60a. **Facebook candidate approval modal** — when an ADMIN starts import from the
    Vaadin UI, pause each discovery pass after candidate detection, show a modal
    in that same admin UI session with candidate URL, source Facebook post URL,
    configured language, and Accept/Reject radio options defaulting to Accept,
    filter out already-imported URLs before approval, then import only accepted
    URLs and include rejected URLs in the final summary. The modal and approval
    logs include a runtime-unique `candidateId` and `sourcePostUrl` for diagnostics.
61. **Lifecycle cleanup** — remove startup auto-run and stop closing the Selenium browser
    after every import; keep the window alive until application shutdown.

---

## Phase 20 — Analytics and consent (NEW)

62. **Analytics configuration** — add env-backed properties
    `GOOGLE_ANALYTICS_MEASUREMENT_ID`, `STATCOUNTER_PROJECT_ID`, and
    `STATCOUNTER_SECURITY_ID`; update `.env.sample-server`, `.env.sample-worker`,
    README, and Compose passthrough.
63. **Conditional script rendering** — render Google Analytics and StatCounter scripts only
    when their required IDs are configured and the user has accepted analytics cookies.
64. **Analytics-only cookie consent** — add a consent banner explaining that tracking is for
    analytics only. Store consent in browser storage/cookie, provide accept/reject controls,
    and provide a way to change/revoke the decision later.

---

## Phase 21 — Article content preservation cache (FUTURE CONSIDERATION)

65. **Article content preservation** — article text/content is captured at creation and
    update time into the `article_content` table (`V5__article_content.sql`). Content is
    stored for archival/preservation only (max 50 000 chars, `truncated` flag). NOT used
    for UI rendering, source fallback, or AI summary.
66. **Potential future uses** — preserved content may later support article display when
    source URL stops responding, AI summary generation, or audit/debugging. Requires
    separate product/legal decisions first.
67. **Content cache constraints** — sanitization/readability extraction, access control,
    copyright/privacy posture, and purge behavior must be decided before any use beyond
    archival.

---

## Phase 22 — Failed enrichment retry queue (FUTURE CONSIDERATION)

68. **TODO: Kafka-backed enrichment failure retry queue** — capture failed article
    create/import requests when URL enrichment returns 422, including cases such as Facebook
    photo URLs where the remote API reports `URL enrichment failed: target returned HTTP 400`.
    A future server-side Kafka queue should persist the original request context and retry on
    a regular schedule once enrichment handling improves. The current synchronous atomic
    behavior remains unchanged: failed enrichment still returns 422 and writes no article at
    request time. Admin/debug notifications should surface recurring failures with URL,
    source/import context, failure reason, attempt count, and last failure timestamp so the
    cases can be investigated and enrichment fixes can be prioritized.

---

## Phase 23 — Regression tests for ownership, metadata, filters, RSS discovery, analytics, and Facebook import (NEW)

69. **Migration tests** — verify creator backfill to oldest user, migration failure when
    articles exist without users, `created_by_user_id NOT NULL`, and nullable `published_at`.
70. **Auth/user tests** — verify soft-deleted users cannot log in or authorize writes, admins
    can restore users, and final active admin cannot be deleted or demoted.
71. **Article tests** — verify creator immutability, authenticated-only creator exposure,
    RSS creator omission, publication date parsing/override/clearing, thumbnail extraction,
    language normalization/validation, filters, and sorting.
72. **UI/manual tests** — verify language dropdown, date filters, publication date picker/time
    picker, creator visibility rules, RSS `<link rel="alternate">` discovery + visible RSS
    link, analytics consent/script rendering, and the Facebook import admin button/job flow.
