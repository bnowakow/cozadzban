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

Starts PostgreSQL on `localhost:${POSTGRES_PORT}` (as set in `.env`).

### 3. Run the application

```sh
make run
```

Uses `SPRING_PROFILES_ACTIVE` from `.env`. Flyway migrations run automatically on startup.

The Vaadin UI is available at **http://localhost:8080/**.

### Other useful targets

```sh
make help          # List all targets
make build         # Build without tests
make test          # Run all tests
make docker-logs   # Follow Docker logs
make dev-down      # Stop and remove containers
```

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
