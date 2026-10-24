# Codex CLI in the Spring Boot container

The Spring Boot image includes a pinned Codex CLI and Node runtime. Java can
find `codex` on `PATH`, and `CODEX_HOME` points to a private `codex-auth` Docker
volume shared by the blue and green application containers. The volume survives
container replacement and `docker compose down`; `down -v` removes it.

This installation is for operator use and feasibility work. An experimental
image-generation job exists for articles without a source thumbnail. It is
disabled by code default; the deployment operator enabled it in the private
`.env`. OpenAI says Codex app-server authentication is not permitted for
commercial or hosted services, so device login here does not authorize enabling
automatic image generation on the hosted site. See
[article image generation plan](ARTICLE_IMAGE_GENERATION_PLAN.md) for the
proposed supported provider path.

## Sign in

After building the updated Spring Boot image, run on the server:

```sh
make codex-docker-login
```

The terminal prints a browser URL and one-time code. Open the URL on your own
device, sign in to ChatGPT, and enter the code. Device-code login must first be
enabled in your ChatGPT security settings or workspace permissions. The browser
does not send tokens to this application's frontend.

Then check the login and CLI version:

```sh
make codex-docker-status
docker compose run --rm --no-deps --entrypoint codex springboot --version
```

The login command uses a running Spring Boot container when one is available,
then verifies that a second container can read the saved login. It falls back
to a short-lived container if the application is stopped. Both blue and green
application containers use the same auth volume after deployment.
`make codex-docker-logout` signs the account out.

## Daily authorization check

Run the check at any time with `make codex-auth-check`. It first confirms the
cached ChatGPT login in a running Java container when available, then makes
one short live Codex request. This is needed
because `codex login status` only confirms that credentials are stored; it does
not prove the account can still complete a request. The live check uses a
read-only, short-lived container with access only to the Codex auth volume and
outbound network. It has no application `.env`, database, or article data mount.
The request consumes a small amount of Codex usage. A failure returns a nonzero
exit status and prints no credential material.

Install both managed cron jobs with `make install-all-crons`. This preserves
unrelated entries in the current user's crontab and replaces the existing
PostgreSQL backup entry, including its legacy marker. The default schedule is
02:00 for PostgreSQL backup and 03:15 for the Codex check, in the cron daemon's
local timezone. Override `CRON_SCHEDULE` or `CODEX_AUTH_CRON_SCHEDULE` when
installing if needed. You can install just the Codex job with
`make install-codex-auth-cron`.

The Codex cron writes to `logs/codex-auth-check.log`. If it fails, the cron
entry also prints a short failure notice for cron mail (when `MAILTO` is
configured). Rerun `make codex-auth-check` to confirm recovery, or
`make codex-docker-login` if the account needs a new device login. The host
needs Docker Compose, `jq`, `timeout`, and access to the Docker daemon.

The volume contains a credential cache that must be handled like a password.
Do not copy it into the repository, logs, images, or browser storage. Anyone
who can access the Docker daemon or the Spring Boot container can access it.

## Verification scope

The Docker build can verify that the executable is present and starts. A
successful device login and live model call require the account owner to finish
the browser step; they cannot be asserted by the image build alone. On the
production server, use Compose checks and `codex --version`. Run Gradle tests
on a development machine, as instructed by `AGENTS.md`.

Official OpenAI docs: [headless device login](https://learn.chatgpt.com/docs/auth),
[login status behavior](https://learn.chatgpt.com/docs/developer-commands),
[Codex app-server auth boundary](https://learn.chatgpt.com/docs/app-server).
