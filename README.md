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

Then edit the matching file for the role you are setting up:

| Variable | Description | Default |
|---|---|---|
| `POSTGRES_DB` | Database name | _(required)_ |
| `POSTGRES_USER` | Database user | _(required)_ |
| `POSTGRES_PASSWORD` | Database password | _(required)_ |
| `POSTGRES_PORT` | Host port for PostgreSQL | `5432` |
| `APP_PORT` | Host-local port for the Dockerized reverse proxy | `8086` |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`local` or `prod`) | `local` |
| `SPRING_DEVTOOLS_RESTART_ENABLED` | Enables Spring Boot DevTools restarts. Use `true` for the server and `false` for the worker so Selenium imports are not interrupted. | server: `true`, worker: `false` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | Google OAuth2 client ID (used to validate JWT `aud` claim) | _(required for auth to work)_ |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | Google OAuth2 client ID (used by UI login) | _(required for UI login)_ |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret (used by UI login) | _(required for UI login)_ |
| `COZADZBAN_BOOTSTRAP_ADMIN_EMAIL` | Email of the first admin user — only required when the `app_user` table has no ADMIN rows (first run). Ignored once an admin exists. | _(required on first run)_ |
| `GOOGLE_ANALYTICS_MEASUREMENT_ID` | Google Analytics 4 Measurement ID (e.g. `G-XXXXXXXXXX`). Leave blank to disable GA. | _(optional)_ |
| `STATCOUNTER_PROJECT_ID` | StatCounter project ID. Leave blank to disable StatCounter. | _(optional)_ |
| `STATCOUNTER_SECURITY_ID` | StatCounter security code for the project. Required when `STATCOUNTER_PROJECT_ID` is set. | _(optional)_ |

> `.env`, `.env.server`, and `.env.worker` are gitignored and must never be committed.

### Optional Facebook profile import

The app can run a Selenium import for posts on
`https://www.facebook.com/bartek.dobrowolski.nowakowski` that contain the marker phrase
`co za dzban`. It is disabled by default and only runs when explicitly triggered.

Minimum configuration:

```sh
APP_FACEBOOK_IMPORT_ENABLED=true
APP_FACEBOOK_IMPORT_USERNAME=you@example.com
APP_FACEBOOK_IMPORT_SCROLLS=8
```

Optional automatic login:

```sh
APP_FACEBOOK_IMPORT_USERNAME=facebook-login@example.com
APP_FACEBOOK_IMPORT_PASSWORD=...
```

Prefer putting the login values in `.env.worker` using `APP_FACEBOOK_IMPORT_USERNAME`,
`APP_FACEBOOK_IMPORT_PASSWORD`, and `APP_FACEBOOK_IMPORT_HEADLESS`. The same username is also used
to resolve the local app user that owns imported articles. If those keys are absent, the app falls
back to `src/main/resources/facebook.properties`.

If you run the importer locally but want it to create articles on the public server instead of the
local database, configure the remote API target and machine key:

```sh
APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL=https://cozadzban.pl
APP_FACEBOOK_IMPORT_TARGET_API_KEY=...
APP_FACEBOOK_IMPORT_TARGET_API_KEY_HEADER=X-CoZaDzban-M2M-Key
APP_FACEBOOK_IMPORT_TARGET_ARTICLE_PATH=/api/articles
```

On the server, configure the matching machine-to-machine credential:

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
the article URL; if none is found, it stores the Facebook post URL and caches the post text as the
article content.

### 2. Start infrastructure

```sh
make dev-up
```

Starts the Docker Compose stack from `compose.yaml`. PostgreSQL is exposed on
`localhost:${POSTGRES_PORT}` and the Dockerized reverse proxy is exposed on
`http://localhost:${APP_PORT:-8086}`. Spring Boot containers are only reachable on the Compose
network; host nginx should proxy to `127.0.0.1:${APP_PORT:-8086}`.

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

Docker runtime data and lightweight deployment assets are stored below `docker-data/`. The Spring
Boot container mounts `./docker-data/data` as `/app/data`, which keeps generated assets such as
downloaded favicons outside the image and available across container rebuilds.

For server upgrades, prefer:

```sh
make docker-upgrade
```

That target uses Docker BuildKit and preserves build caches between runs. It starts the idle
blue/green Spring Boot service, waits for `/actuator/health`, reloads the Compose nginx proxy to
the healthy service, and then stops the old Spring Boot service. Use
`make docker-upgrade-no-cache` only when you intentionally need a completely fresh image build.

### Other useful targets

```sh
make help          # List all targets
make build         # Build without tests
make test          # Run all tests
make docker-logs   # Follow Docker logs
make dev-down      # Stop and remove containers
```

### Docker image

The repository includes a multi-stage `Dockerfile`:

- builder stage: Java 21 JDK image, runs `./gradlew bootJar --no-daemon`
- builder stage cache mounts: Gradle caches, Gradle wrapper, Vaadin tooling, and npm cache
- runtime stage: Java 21 JRE image, copies the executable Spring Boot jar as `/app/app.jar`
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
| `springboot` | `1.25` CPUs | `1536m` |
| `postgres` | `0.75` CPUs | `1024m` |
| `zipkin` | `0.25` CPUs | `384m` |

The stack is capped at about `2.25` CPUs and `2944m` memory total. `memswap_limit` is set equal
to `mem_limit` for each service to avoid heavy swap pressure on the host. The Spring Boot
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

### Production TODOs

- TODO: Fix Nginx virtual host configuration for `www.cozadzban.bnowakowski.pl`.
  Current installer error:
  `Could not automatically find a matching server block for www.cozadzban.bnowakowski.pl. Set the server_name directive to use the Nginx installer.`
- TODO: Update Google OAuth authorized redirect URI from the temporary HTTP callback to
  `https://cozadzban.bnowakowski.pl/login/oauth2/code/google` once HTTPS/DNS is stable.
