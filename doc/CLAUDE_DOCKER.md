# Claude Code in the Spring Boot container

The Spring Boot image includes a pinned Claude Code CLI (`@anthropic-ai/claude-code`,
installed under `/opt/claude`). It is the fallback illustrator for when Codex can't
produce an article image, for example while Codex is at its ChatGPT usage limit.
`CLAUDE_CONFIG_DIR` points to a private `claude-auth` Docker volume shared by the blue
and green containers, the same way Codex uses `codex-auth`.

Claude has no image generation. Claude Code is asked for a flat vector **SVG**
illustration instead. The app checks the SVG and renders it to the same 1200×630 PNG
that Codex images use (see [Article image generation](ARTICLE_IMAGE_GENERATION_PLAN.md)).
These images look simpler and flatter than Codex's.

## Terms

This setup signs in with a personal Claude subscription (Pro or Max), the same way
Codex uses a ChatGPT login here. Anthropic's subscription plans are meant for an
individual's own use. Before relying on this for the public site, check Anthropic's
current terms. If automated use for a hosted service isn't covered, switch to an
Anthropic API key (`claude auth login --console`, or `ANTHROPIC_API_KEY`) or disable
the fallback.

## Sign in

After building the updated image, run on the server:

```sh
make claude-docker-login
```

Claude Code prints a sign-in URL. Open it on your own device, sign in with the
subscription account, and paste the code back into the terminal. The script then
verifies from a second container that the login was saved to the shared volume.
Check it any time with:

```sh
make claude-docker-status
docker compose run --rm --no-deps --entrypoint claude springboot --version
```

**Alternative: a long-lived token.** `make claude-docker-token` runs `claude setup-token`.
Put the token it prints into the server `.env` as `APP_FACEBOOK_IMAGE_CLAUDE_OAUTH_TOKEN`,
then restart or redeploy the app. The app passes it to Claude Code as
`CLAUDE_CODE_OAUTH_TOKEN`. Treat it like a password.

Sign out with `make claude-docker-logout`.

## Enable the fallback

In the server `.env`:

```sh
APP_FACEBOOK_IMAGE_CLAUDE_ENABLED=true
APP_FACEBOOK_IMAGE_CLAUDE_MODEL=sonnet        # alias or full model name
APP_FACEBOOK_IMAGE_CLAUDE_TIMEOUT_SECONDS=180
```

Then redeploy with `make docker-upgrade`. With Codex disabled and Claude enabled,
Claude illustrates articles on its own.

## How a run works

- The app runs `claude -p --output-format text --model <model> --tools ""
  --strict-mcp-config --disable-slash-commands --no-session-persistence` in an empty
  temporary directory. Every tool is disabled, so Claude can only answer with text.
- The child process gets only `HOME`, `CLAUDE_CONFIG_DIR`, `PATH`, `LANG`, the
  auto-updater and telemetry switches, and the optional OAuth token. No other
  application secrets.
- Article fields are passed as untrusted reference data.
- The SVG must contain plain shapes only (`path`, `rect`, `circle`, gradients, clip
  paths and similar). Scripts, text, embedded images, `<style>`, `style` and event
  attributes, `DOCTYPE`s and references outside the document are rejected, not
  cleaned. Batik renders it with external resources disabled.
- A Claude usage-limit message pauses the image queue the same way a Codex one does.
  If the reset time can't be read, the pause is one hour. When both are limited, the
  earlier reset wins.
- Images are stored and labelled like Codex images: the `CODEX_GENERATED` image source
  and the "AI-generated illustration" disclosure. The job's error message says which
  tool failed.

## Monitoring

`make claude-auth-check` checks the saved login (or the configured token) and makes one
small live request from an isolated container (`claude-auth-check`, Compose `ops`
profile). `make install-claude-auth-cron` runs it daily at 03:30 and logs to
`logs/claude-auth-check.log`. `make install-all-crons` includes it.
