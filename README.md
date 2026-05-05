# Co za zjeb

A news article aggregator with Google OIDC authentication.

## Prerequisites

- Java 21+
- Docker (with `docker compose`)
- `make`

## Local environment setup

### 1. Create `.env`

Copy the sample and fill in your values:

```sh
cp .env.sample .env
```

Edit `.env`:

| Variable | Description | Default |
|---|---|---|
| `POSTGRES_DB` | Database name | _(required)_ |
| `POSTGRES_USER` | Database user | _(required)_ |
| `POSTGRES_PASSWORD` | Database password | _(required)_ |
| `POSTGRES_PORT` | Host port for PostgreSQL | `5432` |
| `APP_PORT` | Host port for the Dockerized Spring Boot app | `8080` |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`local` or `prod`) | `local` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | Google OAuth2 client ID (used to validate JWT `aud` claim) | _(required for auth to work)_ |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | Google OAuth2 client ID (used by UI login) | _(required for UI login)_ |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret (used by UI login) | _(required for UI login)_ |
| `COZAZJEB_BOOTSTRAP_ADMIN_EMAIL` | Email of the first admin user — only required when the `app_user` table has no ADMIN rows (first run). Ignored once an admin exists. | _(required on first run)_ |

> `.env` is gitignored and must never be committed.

### 2. Start infrastructure

```sh
make dev-up
```

Starts the Docker Compose stack from `compose.yaml`. PostgreSQL is exposed on
`localhost:${POSTGRES_PORT}` and the Dockerized Spring Boot app is exposed on
`http://localhost:${APP_PORT:-8080}`.

The `app` container runs with the `prod` Spring profile and connects to PostgreSQL through the
Compose network using:

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

The Vaadin UI is available at **http://localhost:8080/**.

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
- runtime stage: Java 21 JRE image, copies the executable Spring Boot jar as `/app/app.jar`
- runtime user: non-root `spring`
- exposed port: `8080`

Build it manually with:

```sh
docker build -t cozazjeb:local .
```

### Docker resource limits

`compose.yaml` sets conservative runtime limits for a small 4 vCPU / 8 GB VM that also runs
WordPress and MySQL containers:

| Service | CPU limit | Memory limit |
|---|---:|---:|
| `app` | `1.25` CPUs | `1536m` |
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
| `COZAZJEB_BOOTSTRAP_ADMIN_EMAIL` | First admin email (required until at least one ADMIN row exists) |

### Production TODOs

- TODO: Fix Nginx virtual host configuration for `www.cozazjeb.bnowakowski.pl`.
  Current installer error:
  `Could not automatically find a matching server block for www.cozazjeb.bnowakowski.pl. Set the server_name directive to use the Nginx installer.`
