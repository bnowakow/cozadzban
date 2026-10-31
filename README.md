# Co za dzban

A news article aggregator with Google OIDC authentication.

## Prerequisites

- Java 21+
- Docker (with `docker compose`)
- `make`

## Local environment setup

### 1. Pick the right env template

The project now keeps two templates:

- `.env.sample-server` for the public server
- `.env.sample-worker` for the local Facebook import worker

Copy the one you need to the private env file you will actually run with:

```sh
cp .env.sample-server .env.server
cp .env.sample-worker .env.worker
```

Then edit the matching file for the role you are setting up. Activate it before running
Make, Gradle, or Compose; these read `.env`, not `.env.server` or `.env.worker`:

```sh
cp .env.server .env   # public server
# Or, on the local import worker:
cp .env.worker .env
```

`make sync-env-files` deploys `.env.server` to the configured remote server and
activates `.env.worker` locally. It requires SSH access to that server.

Key settings:

| Variable | Description | Default |
|---|---|---|
| `POSTGRES_DB` | Database name | _(required)_ |
| `POSTGRES_USER` | Database user | _(required)_ |
| `POSTGRES_PASSWORD` | Database password | _(required)_ |
| `POSTGRES_PORT` | Host port for PostgreSQL | `5432` |
| `APP_PORT` | Host-local port for the Dockerized reverse proxy | `8086` |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`local` or `prod`) | `local` |
| `SPRING_DEVTOOLS_RESTART_ENABLED` | Controls DevTools restarts for local JVM runs; the Docker production image excludes DevTools. Keep it `false` on the Selenium worker so imports are not interrupted. | server template: `true`, worker template: `false` |
| `APP_LOG_FILE` | Spring Boot log file path, relative to the app working directory unless absolute. Use separate files for server and worker. | `logs/cozadzban.log` |
| `APP_FACEBOOK_IMPORT_SELENIUM_REUSE_BROWSER_ACROSS_RESTARTS` | Reconnects to an existing non-headless Firefox Selenium session after a local worker restart. | `true` |
| `APP_FACEBOOK_IMPORT_SELENIUM_DRIVER_SESSION_FILE` | File used to remember the reusable Firefox WebDriver server URL and session ID. | `logs/facebook-import-firefox-session.properties` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | Google OAuth2 client ID (used to validate JWT `aud` claim) | _(required for auth to work)_ |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | Google OAuth2 client ID (used by UI login) | _(required for UI login)_ |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret (used by UI login) | _(required for UI login)_ |
| `COZADZBAN_BOOTSTRAP_ADMIN_EMAIL` | Email of the first admin user — only required when the `app_user` table has no ADMIN rows (first run). Ignored once an admin exists. | _(required on first run)_ |
| `GOOGLE_ANALYTICS_MEASUREMENT_ID` | Google Analytics 4 Measurement ID (e.g. `G-XXXXXXXXXX`). Leave blank to disable GA. | _(optional)_ |
| `GOOGLE_ANALYTICS_API_SECRET` | Google Analytics 4 Measurement Protocol API secret. Required for server-side `/rss` reached events. | _(optional)_ |
| `STATCOUNTER_PROJECT_ID` | StatCounter project ID. Leave blank to disable StatCounter. | _(optional)_ |
| `STATCOUNTER_SECURITY_ID` | StatCounter security code for the project. Required when `STATCOUNTER_PROJECT_ID` is set. | _(optional)_ |
| `APP_FACEBOOK_PAGE_APP_ID` | Meta app ID for connecting Facebook Pages | _(optional until Page publishing is configured)_ |
| `APP_FACEBOOK_PAGE_APP_SECRET` | Meta app secret, server-side only | _(optional until Page publishing is configured)_ |
| `APP_FACEBOOK_PAGE_CONFIGURATION_ID` | Facebook Login for Business configuration ID for Page access | _(required to connect a Page)_ |
| `APP_FACEBOOK_PAGE_REDIRECT_URI` | Exact Meta OAuth redirect URI registered for the app | _(required; e.g. `https://cozadzban.pl/facebook-pages/oauth/callback`)_ |
| `APP_FACEBOOK_PAGE_ENCRYPTION_KEY` | Stable 16-, 24-, or 32-byte AES key (base64 or hex) for stored Page tokens | _(required to connect a Page)_ |
| `APP_FACEBOOK_PAGE_GRAPH_VERSION` | Meta Graph API version used for Page calls | `v26.0` |
| `APP_FACEBOOK_IMAGE_CODEX_ENABLED` | Enables the experimental generated-image fallback for missing or rejected source thumbnails | `false` |
| `APP_FACEBOOK_IMPORT_APIFY_SCHEDULE_INTERVAL` | Minimum time between enabled Apify imports; the worker template schedules one automatic run per 24 hours | `24h` |
| `APP_FACEBOOK_IMPORT_APIFY_COST_SUMMARY_DAYS` | Lookback window for the admin Apify cost total and automatic-run average | `30` |

> `.env`, `.env.server`, and `.env.worker` are gitignored and must never be committed.

### Connect Facebook Pages (phase 1)

Create a Meta app with Facebook Login for Business. In **Configurations**, create
a Page access configuration using a User access token and the permissions
`pages_show_list`, `pages_read_engagement`, `pages_manage_posts`, and
`pages_manage_engagement`. Copy its configuration ID into
`APP_FACEBOOK_PAGE_CONFIGURATION_ID` and register the exact callback URI above. Configure
the server variables in `.env.server`, sign in as an ADMIN, then open
**Admin → Facebook Pages**. Select an article language already used by an article,
authorize Meta, choose a Page that grants content creation, and save its settings. Each language can
have one connected Page. Page tokens are encrypted in PostgreSQL and never
sent to browser JavaScript. New connections start disabled.

If Meta returns no Pages during connection, confirm that the Facebook account
has Page content access and that the Page was selected in the Meta consent dialog.
For a Page linked to a Meta business portfolio, add `business_management` to
the login configuration and give that person a role in the portfolio, then
authorize again. Meta's `/me/accounts` endpoint omits such Pages without both.

Phase 1 connects and configures Pages. The later publishing, engagement, and
story statistics workflows are described below and in `doc/SPEC.yaml`.

### Automatic Facebook image posts (phase 2)

For every newly created article, the article transaction now queues one Page
publication. An enabled Page must exist for that article language at creation
time. The background worker publishes a photo with the article title and a
short lead, without an article link. It normally uses a usable source thumbnail.
When the experimental generated-image workflow is enabled, missing or rejected
source thumbnails can produce a labeled illustration; the branded PNG remains
the fallback. Published story pages link to their Facebook posts for reader comments.

The Meta login configuration must grant `pages_manage_posts` in addition to
the Phase 1 permissions. **Admin → Facebook Pages** shows failed jobs and
uncertain results. A Meta timeout is never retried automatically: an ADMIN
must inspect the Page, then record the existing post or explicitly confirm
that no post appeared before retrying. Articles created while their Page is
disabled remain `UNCONFIGURED` and are not backfilled automatically.

`FacebookPostImageProvider` selects the source, generated, or placeholder image.
The ADMIN-only `/admin/facebook-thumbnails` view shows image job status and
allows manual generation. See the [image generation plan](doc/ARTICLE_IMAGE_GENERATION_PLAN.md)
for the experimental provider and remaining production decisions.

### Engagement, link comments, and story statistics (phases 3–4)

An enabled Page's scheduler reads reaction totals for published posts and ranks
eligible articles by the latest successful count. Once a post meets the Page's
configurable minimum age, the scheduler may add the canonical `/stories/{id}`
URL as a comment. The default budget is five confirmed link comments per Page
calendar month in its configured time zone. **Admin → Facebook Pages** exposes
reaction checks, comment problems, and post update or deletion problems for
review. Uncertain Graph results require inspection before retrying.

The public story page shows the source icon and name, a back link, its image
through `/rss/image/{id}`, and a link to the Facebook post when available.
Qualifying browser loads are counted as daily UTC aggregates after bot filtering.
**Admin → Story and Facebook statistics** (`/admin/facebook-statistics`) shows
per-article views, Facebook reactions, post and link-comment status, and Page
totals. Reactions remain the link-selection signal; story views are collected
for a future ranking rule. See the [phase 3](doc/FACEBOOK_PHASE3_TESTING.md)
and [phase 4](doc/FACEBOOK_PHASE4_TESTING.md) manual checks.

Public pages for the Meta app settings:

- Privacy Policy: `https://cozadzban.pl/privacy-policy.html`
- User data deletion instructions: `https://cozadzban.pl/user-data-deletion.html`

### Optional Facebook profile import

The app can run a Selenium import for posts on
`https://www.facebook.com/bartek.dobrowolski.nowakowski` that contain the marker phrase
`co za dzban`. It is disabled by default. When enabled, ADMIN users can trigger it manually;
`APP_FACEBOOK_IMPORT_SCHEDULE_ENABLED=true` enables periodic runs and
`APP_FACEBOOK_IMPORT_SCHEDULE_RUN_ON_STARTUP=true` enables a worker-startup run.
Apify has separate enablement and scheduling settings in the env templates.

Minimum configuration:

```sh
APP_FACEBOOK_IMPORT_SELENIUM_ENABLED=true
APP_FACEBOOK_IMPORT_SELENIUM_USERNAME=you@example.com
APP_FACEBOOK_IMPORT_SELENIUM_SCROLLS=8
```

Optional automatic login:

```sh
APP_FACEBOOK_IMPORT_SELENIUM_USERNAME=facebook-login@example.com
APP_FACEBOOK_IMPORT_SELENIUM_PASSWORD=...
```

Prefer putting the login values in `.env.worker` using `APP_FACEBOOK_IMPORT_SELENIUM_USERNAME`,
`APP_FACEBOOK_IMPORT_SELENIUM_PASSWORD`, and `APP_FACEBOOK_IMPORT_SELENIUM_HEADLESS`. The same username is also used
to resolve the local app user that owns imported articles. If those keys are absent, the app falls
back to `src/main/resources/facebook.properties`.

If you run the importer locally but want it to submit proposals to the public server instead of the
local database, configure the remote API target and machine key:

```sh
APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL=https://cozadzban.pl
APP_FACEBOOK_IMPORT_TARGET_API_KEY=...
APP_FACEBOOK_IMPORT_TARGET_API_KEY_HEADER=X-CoZaDzban-M2M-Key
APP_FACEBOOK_IMPORT_TARGET_PROPOSAL_PATH=/api/facebook-import/proposals
APP_FACEBOOK_IMPORT_TARGET_PROPOSAL_EXISTS_PATH=/api/facebook-import/proposals/exists
APP_FACEBOOK_IMPORT_TARGET_RUN_PATH=/api/facebook-import/runs
```

On the server, configure the matching machine-to-machine credential. The principal email must belong
to an ACTIVE app user; accepted proposals create Articles owned by this bot user:

```sh
APP_MACHINE_AUTH_ENABLED=true
APP_MACHINE_AUTH_HEADER_NAME=X-CoZaDzban-M2M-Key
APP_MACHINE_AUTH_API_KEY=...
APP_MACHINE_AUTH_PRINCIPAL_EMAIL=facebook-import-bot@cozadzban.pl
```

Generate the shared secret once, then paste the same value into both places. A simple option is:

```sh
openssl rand -hex 32
```

Alternatively, copy `src/main/resources/facebook.properties.sample` to
`src/main/resources/facebook.properties` and put `username` and `password` there. That file is
gitignored because it contains the Facebook password.

If you run the app through `docker compose`, keep the worker values in the active `.env` file as
well. The Compose file forwards them into the container, while a local JVM run reads them directly
from `.env`.

If no Facebook credentials are configured, a non-headless Selenium browser opens and waits for
manual login. For each marked post, the importer uses the first non-Facebook link in the post as
the article URL; if none is found, it proposes an importable Facebook post/reel/photo URL. The
worker no longer creates articles directly. It submits article proposals with guessed language,
source Facebook post URL, and compressed diagnostics. Logged-in users review them at
`/article-proposals`, correct language if needed, and accept/reject them there.

### 2. Start infrastructure

```sh
make docker-up
```

Starts PostgreSQL, Zipkin, the Spring Boot app, and the reverse proxy from
`compose.yaml`. For only the infrastructure needed by a local Gradle run, use
`docker compose -f compose-dev.yaml up -d`; the local Spring profile also manages
that development stack through Spring Boot Docker Compose support.

PostgreSQL is exposed on
`localhost:${POSTGRES_PORT}` and the Dockerized reverse proxy is exposed on
`http://localhost:${APP_PORT:-8086}`. Spring Boot containers are only reachable on the Compose
network; host nginx should proxy to `127.0.0.1:${APP_PORT:-8086}`.

For public URL redirects, www TLS troubleshooting, and SEO scan verification, see
[SEO deployment checks](doc/seo-deployment.md).

The active Spring Boot container runs with the `prod` Spring profile and connects to PostgreSQL
through the Compose network using:

```text
jdbc:postgresql://postgres:5432/${POSTGRES_DB}
```

### 3. Run the application from Gradle

```sh
make run
```

For a local JVM run, use `make run`. It uses `SPRING_PROFILES_ACTIVE` from `.env` and connects
to PostgreSQL on `localhost:${POSTGRES_PORT}`.

To run the full Dockerized application instead, use:

```sh
docker compose -f compose.yaml up --build
```

The Vaadin UI is available through the Compose reverse proxy at
**http://localhost:${APP_PORT:-8086}/**.

Swagger UI is available for a local JVM run at
**http://localhost:8080/swagger-ui/index.html**. The raw OpenAPI document is available at
**http://localhost:8080/v3/api-docs**. When running through the Compose reverse proxy, use the
same paths on `http://localhost:${APP_PORT:-8086}`.

Docker runtime data and lightweight deployment assets are stored below `docker-data/`. The Spring
Boot container mounts `./docker-data/data` as `/app/data`, which keeps generated assets such as
downloaded favicons outside the image and available across container rebuilds. Facebook and
Instagram source thumbnails are saved under `docker-data/data/meta-source-images` when first
requested and backfilled in small batches. Set `APP_META_SOURCE_IMAGE_CACHE_DIR` to change that
directory. Completed generated illustrations are served at `/generated-images/{id}.png`.

For server upgrades, prefer:

```sh
make docker-upgrade
```

That target uses Docker BuildKit and preserves build caches between runs. It starts the idle
blue/green Spring Boot service, waits for `/actuator/health/readiness`, reloads the Compose nginx proxy to
the healthy service, and then stops the old Spring Boot service. Use
`make docker-upgrade-no-cache` only when you intentionally need a completely fresh image build.

The deployment host can check the active Spring Boot service once per minute without adding a
monitoring container. Install the cron job with `make install-app-health-cron`, or include it with
the other managed jobs using `make install-all-crons`. Run `make app-health-check` for an immediate
check. The watchdog records three consecutive failed readiness checks before considering a
restart, checks the app directly to distinguish proxy and dependency failures, and waits at least
15 minutes between restarts. After a restart command finishes, it allows three minutes for
readiness to recover without counting new failures. It skips checks while `docker-upgrade` holds
the deployment lock.
Events are recorded in `logs/app-health-check.log`; successful cron checks are silent. A restart
does not resolve the cause of a process or thread limit being reached.
The watchdog saves a limited pre-restart snapshot and JVM thread dump under
`logs/app-health-diagnostics/`, retaining snapshots for seven days. It permits at most two restart
attempts per hour and sends outage, restart-limit, and recovery notifications through the host's
`mail` command when `APP_HEALTH_ALERT_EMAIL` or cron's `MAILTO` is set.

### Other useful targets

```sh
make help          # List all targets
make build         # Build without tests
make test          # Run all tests
make docker-logs   # Follow Docker logs
make docker-down   # Stop and remove containers
make performance-report  # Measure front-page performance (see doc/PAGE_PERFORMANCE.md)
```

### Additional docs

- [Project documentation](doc/README.md) - specs, implementation plan, manual testing guides, and dev aids.
- [Codex and Claude skills](doc/codex-skills/README.md) - repository-provided skills and prompts for Codex and Claude Code.
- [Utilities](utilities/README.md) - helper scripts for imports, local data, and manual browser tasks.

### Docker image

The repository includes a multi-stage `Dockerfile`:

- Codex CLI stage: Node 22 image, installs the pinned `@openai/codex@0.164.0` CLI under `/opt/codex`
- builder stage: Java 21 JDK image, runs `./gradlew bootJar --no-daemon`
- builder stage cache mounts: Gradle caches, Gradle wrapper, Vaadin tooling, and npm cache
- runtime stage: Java 21 JRE image, copies the executable Spring Boot jar as `/app/app.jar`, plus the
  Node runtime and Codex CLI on `PATH`, with `CODEX_HOME=/home/spring/.codex` (see [Codex in Docker](doc/CODEX_DOCKER.md))
- runtime user: non-root `spring`
- exposed port: `8080`

Build it manually with:

```sh
docker build -t cozadzban:local .
```

For equivalent cached manual builds, enable BuildKit:

```sh
DOCKER_BUILDKIT=1 docker build -t cozadzban:local .
```

### Docker resource limits

`compose.yaml` sets conservative runtime limits for a small 4 vCPU / 8 GB VM that also runs
WordPress and MySQL containers:

| Service | CPU limit | Memory limit |
|---|---:|---:|
| `springboot` (each blue/green instance) | `0.75` CPUs | `1536m` |
| `postgres` | `0.50` CPUs | `512m` |
| `zipkin` | `0.15` CPUs | `256m` |

These three services total `1.40` CPUs and `2304m` memory with one app instance,
or `2.15` CPUs and `3840m` during blue/green overlap. The nginx reverse proxy has
no explicit CPU or memory limit, so these totals exclude it. The on-demand `codex-auth-check`
container (Compose `ops` profile, used by `make codex-auth-check`) is limited to `256m` memory
and 32 processes and is also excluded. `memswap_limit` is set equal to `mem_limit` for the three
services above to avoid heavy swap pressure on the host. The Spring Boot
container also sets `JAVA_TOOL_OPTIONS` so the JVM heap stays inside the container memory limit.

## Production

Set the following environment variables on your deployment platform (no `.env` file needed in prod):

| Variable | Description |
|---|---|
| `SPRING_PROFILES_ACTIVE` | Must be `prod` |
| `JDBC_DATABASE_URL` | Full JDBC URL |
| `JDBC_DATABASE_USERNAME` | Database user |
| `JDBC_DATABASE_PASSWORD` | Database password |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | Google OAuth2 client ID |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | Google OAuth2 client ID |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret |
| `COZADZBAN_BOOTSTRAP_ADMIN_EMAIL` | First admin email (required until at least one ADMIN row exists) |

### Production reverse proxy and OAuth

Configure the host HTTPS virtual host for `cozadzban.pl` to forward to the Compose
proxy at `127.0.0.1:${APP_PORT:-8086}`. Register
`https://cozadzban.pl/login/oauth2/code/google` as the Google OAuth redirect URI
and set `SESSION_COOKIE_SECURE=true` for HTTPS deployments. Forwarded headers
must preserve the public HTTPS scheme through the proxy chain.

The host HTTPS proxy gzips responses; the Compose nginx does not compress. The
Compose nginx serves `/VAADIN/build/*` with `Cache-Control: public,
max-age=31536000, immutable`, because every file there has a content hash in its
name. Successful responses get that header; errors don't. After editing
`docker-data/nginx/default.conf`, run `docker compose restart reverse-proxy`
instead of `nginx -s reload`: the file is bind-mounted, and an editor that
replaces the file leaves the running container reading the old one.
